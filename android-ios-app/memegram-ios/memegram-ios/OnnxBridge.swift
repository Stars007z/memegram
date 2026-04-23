import Foundation
import ComposeApp
#if canImport(OnnxRuntimeBindings)
import OnnxRuntimeBindings
#endif

final class OnnxBridge: NSObject, OnnxBridgeDelegate {

    static let shared = OnnxBridge()

    #if canImport(OnnxRuntimeBindings)
    private let env: ORTEnv? = {
        do { return try ORTEnv(loggingLevel: .warning) }
        catch { print("[OnnxBridge] ORTEnv init failed: \(error)"); return nil }
    }()

    private var sessions: [Int64: ORTSession] = [:]
    #endif

    private var nextHandle: Int64 = 1
    private let lock = NSLock()

    private var _lastLoadError: String? = nil
    var lastLoadError: String? {
        lock.lock(); defer { lock.unlock() }
        return _lastLoadError
    }

    func loadSession(modelPath: String) -> Int64 {
        #if canImport(OnnxRuntimeBindings)
        guard let env = env else {
            lock.lock(); _lastLoadError = "ORTEnv not initialized"; lock.unlock()
            return 0
        }
        do {
            let opts = try ORTSessionOptions()
            try opts.setIntraOpNumThreads(2)
            try opts.setGraphOptimizationLevel(.basic)
            let session = try ORTSession(env: env, modelPath: modelPath, sessionOptions: opts)
            lock.lock(); defer { lock.unlock() }
            _lastLoadError = nil
            let h = nextHandle; nextHandle += 1
            sessions[h] = session
            return h
        } catch {
            let msg = "\(error)"
            print("[OnnxBridge] loadSession failed for \(modelPath): \(msg)")
            lock.lock(); _lastLoadError = msg; lock.unlock()
            return 0
        }
        #else
        print("[OnnxBridge] onnxruntime-objc not linked — loadSession is a no-op")
        lock.lock(); _lastLoadError = "onnxruntime-objc not linked"; lock.unlock()
        return 0
        #endif
    }

    func closeSession(handle: Int64) {
        #if canImport(OnnxRuntimeBindings)
        lock.lock(); defer { lock.unlock() }
        sessions.removeValue(forKey: handle)
        #endif
    }

    func run(
        handle: Int64,
        int64Names: KotlinArray<NSString>,
        int64Data: KotlinArray<KotlinLongArray>,
        int64Shapes: KotlinArray<KotlinLongArray>,
        floatNames: KotlinArray<NSString>,
        floatData: KotlinArray<KotlinFloatArray>,
        floatShapes: KotlinArray<KotlinLongArray>,
        outputNames: KotlinArray<NSString>
    ) -> KotlinArray<OnnxOutput> {
        #if canImport(OnnxRuntimeBindings)
        lock.lock()
        guard let session = sessions[handle] else {
            lock.unlock()
            print("[OnnxBridge] run: invalid handle \(handle)")
            return KotlinArray<OnnxOutput>(size: 0) { _ in OnnxOutput(data: KotlinFloatArray(size: 0), shape: KotlinLongArray(size: 0)) }
        }
        lock.unlock()

        do {
            var inputs: [String: ORTValue] = [:]
            let int64Count = Int(int64Names.size)
            let floatCount = Int(floatNames.size)
            inputs.reserveCapacity(int64Count + floatCount)

            for i in 0..<int64Count {
                let name = int64Names.get(index: Int32(i))! as String
                let arr = int64Data.get(index: Int32(i))!
                let shape = int64Shapes.get(index: Int32(i))!
                inputs[name] = try makeInt64Tensor(array: arr, shape: shape)
            }
            for i in 0..<floatCount {
                let name = floatNames.get(index: Int32(i))! as String
                let arr = floatData.get(index: Int32(i))!
                let shape = floatShapes.get(index: Int32(i))!
                inputs[name] = try makeFloatTensor(array: arr, shape: shape)
            }

            let outNames: [String] = (0..<Int(outputNames.size)).map { outputNames.get(index: Int32($0))! as String }
            let outSet = Set(outNames)
            let outputsDict = try session.run(
                withInputs: inputs,
                outputNames: outSet,
                runOptions: nil
            )

            let result = KotlinArray<OnnxOutput>(size: Int32(outNames.count)) { idx in
                let name = outNames[Int(truncating: idx)]
                guard let v = outputsDict[name] else {
                    print("[OnnxBridge] missing output: \(name)")
                    return OnnxOutput(data: KotlinFloatArray(size: 0), shape: KotlinLongArray(size: 0))
                }
                do {
                    let info = try v.tensorTypeAndShapeInfo()
                    let shape = info.shape.map { Int64(truncating: $0) }
                    let data = try v.tensorData() as Data
                    let count = data.count / MemoryLayout<Float>.size
                    let kotlinFloats = KotlinFloatArray(size: Int32(count))
                    data.withUnsafeBytes { (raw: UnsafeRawBufferPointer) in
                        let src = raw.bindMemory(to: Float.self).baseAddress!
                        for j in 0..<count { kotlinFloats.set(index: Int32(j), value: src[j]) }
                    }
                    let shapeKArr = KotlinLongArray(size: Int32(shape.count))
                    for (j, s) in shape.enumerated() { shapeKArr.set(index: Int32(j), value: s) }
                    return OnnxOutput(data: kotlinFloats, shape: shapeKArr)
                } catch {
                    print("[OnnxBridge] output extract error: \(error)")
                    return OnnxOutput(data: KotlinFloatArray(size: 0), shape: KotlinLongArray(size: 0))
                }
            }
            return result
        } catch {
            print("[OnnxBridge] run error: \(error)")
            return KotlinArray<OnnxOutput>(size: 0) { _ in OnnxOutput(data: KotlinFloatArray(size: 0), shape: KotlinLongArray(size: 0)) }
        }
        #else
        return KotlinArray<OnnxOutput>(size: 0) { _ in OnnxOutput(data: KotlinFloatArray(size: 0), shape: KotlinLongArray(size: 0)) }
        #endif
    }

    #if canImport(OnnxRuntimeBindings)
    private func makeInt64Tensor(array: KotlinLongArray, shape: KotlinLongArray) throws -> ORTValue {
        let count = Int(array.size)
        var buf = Data(count: count * MemoryLayout<Int64>.size)
        buf.withUnsafeMutableBytes { (raw: UnsafeMutableRawBufferPointer) in
            let dst = raw.bindMemory(to: Int64.self).baseAddress!
            for i in 0..<count { dst[i] = array.get(index: Int32(i)) }
        }
        let shapeArr: [NSNumber] = (0..<Int(shape.size)).map { NSNumber(value: shape.get(index: Int32($0))) }
        return try ORTValue(
            tensorData: NSMutableData(data: buf),
            elementType: .int64,
            shape: shapeArr
        )
    }

    private func makeFloatTensor(array: KotlinFloatArray, shape: KotlinLongArray) throws -> ORTValue {
        let count = Int(array.size)
        var buf = Data(count: count * MemoryLayout<Float>.size)
        buf.withUnsafeMutableBytes { (raw: UnsafeMutableRawBufferPointer) in
            let dst = raw.bindMemory(to: Float.self).baseAddress!
            for i in 0..<count { dst[i] = array.get(index: Int32(i)) }
        }
        let shapeArr: [NSNumber] = (0..<Int(shape.size)).map { NSNumber(value: shape.get(index: Int32($0))) }
        return try ORTValue(
            tensorData: NSMutableData(data: buf),
            elementType: .float,
            shape: shapeArr
        )
    }
    #endif
}
