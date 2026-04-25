import Foundation
import ComposeApp
#if canImport(OnnxRuntimeBindings)
import OnnxRuntimeBindings
#endif

final class OnnxBridge: NSObject, OnnxBridgeDelegate {

    static let shared = OnnxBridge()

    #if canImport(OnnxRuntimeBindings)
    private final class SessionEntry {
        let session: ORTSession
        var persistentInt64: [String: ORTValue] = [:]
        var persistentFloat: [String: ORTValue] = [:]
        var persistentBacking: [String: NSMutableData] = [:]
        init(session: ORTSession) { self.session = session }
    }

    private let env: ORTEnv? = {
        do { return try ORTEnv(loggingLevel: .warning) }
        catch { print("[OnnxBridge] ORTEnv init failed: \(error)"); return nil }
    }()

    private var sessions: [Int64: SessionEntry] = [:]
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
        return autoreleasepool { () -> Int64 in
            guard let env = env else {
                lock.lock(); _lastLoadError = "ORTEnv not initialized"; lock.unlock()
                return 0
            }
            do {
                let opts = try ORTSessionOptions()
                try opts.setIntraOpNumThreads(2)
                try opts.setGraphOptimizationLevel(.basic)
                try opts.addConfigEntry(withKey: "session.disable_cpu_mem_arena", value: "1")
                try opts.addConfigEntry(withKey: "session.disable_mem_pattern", value: "1")
                let session = try ORTSession(env: env, modelPath: modelPath, sessionOptions: opts)
                lock.lock(); defer { lock.unlock() }
                _lastLoadError = nil
                let h = nextHandle; nextHandle += 1
                sessions[h] = SessionEntry(session: session)
                return h
            } catch {
                let msg = "\(error)"
                print("[OnnxBridge] loadSession failed for \(modelPath): \(msg)")
                lock.lock(); _lastLoadError = msg; lock.unlock()
                return 0
            }
        }
        #else
        print("[OnnxBridge] onnxruntime-objc not linked — loadSession is a no-op")
        lock.lock(); _lastLoadError = "onnxruntime-objc not linked"; lock.unlock()
        return 0
        #endif
    }

    func closeSession(handle: Int64) {
        #if canImport(OnnxRuntimeBindings)
        autoreleasepool {
            lock.lock(); defer { lock.unlock() }
            sessions.removeValue(forKey: handle)
        }
        #endif
    }

    func clearPersistentInputs(handle: Int64) {
        #if canImport(OnnxRuntimeBindings)
        autoreleasepool {
            lock.lock(); defer { lock.unlock() }
            guard let entry = sessions[handle] else { return }
            entry.persistentInt64.removeAll(keepingCapacity: false)
            entry.persistentFloat.removeAll(keepingCapacity: false)
            entry.persistentBacking.removeAll(keepingCapacity: false)
        }
        #endif
    }

    func setPersistentInt64Input(handle: Int64, name: String, data: KotlinLongArray, shape: KotlinLongArray) -> Bool {
        #if canImport(OnnxRuntimeBindings)
        return autoreleasepool { () -> Bool in
            lock.lock()
            guard let entry = sessions[handle] else { lock.unlock(); return false }
            lock.unlock()
            do {
                let (value, backing) = try buildInt64Tensor(array: data, shape: shape)
                lock.lock()
                entry.persistentInt64[name] = value
                entry.persistentBacking[name] = backing
                lock.unlock()
                return true
            } catch {
                print("[OnnxBridge] setPersistentInt64Input(\(name)) error: \(error)")
                return false
            }
        }
        #else
        return false
        #endif
    }

    func setPersistentFloatInput(handle: Int64, name: String, data: KotlinFloatArray, shape: KotlinLongArray) -> Bool {
        #if canImport(OnnxRuntimeBindings)
        return autoreleasepool { () -> Bool in
            lock.lock()
            guard let entry = sessions[handle] else { lock.unlock(); return false }
            lock.unlock()
            do {
                let (value, backing) = try buildFloatTensor(array: data, shape: shape)
                lock.lock()
                entry.persistentFloat[name] = value
                entry.persistentBacking[name] = backing
                lock.unlock()
                return true
            } catch {
                print("[OnnxBridge] setPersistentFloatInput(\(name)) error: \(error)")
                return false
            }
        }
        #else
        return false
        #endif
    }

    func runArgmaxLastStep(
        handle: Int64,
        int64Names: KotlinArray<NSString>,
        int64Data: KotlinArray<KotlinLongArray>,
        int64Shapes: KotlinArray<KotlinLongArray>,
        logitsOutputName: String,
        lastStepIndex: Int32,
        vocabSize: Int32
    ) -> Int32 {
        #if canImport(OnnxRuntimeBindings)
        return autoreleasepool { () -> Int32 in
            lock.lock()
            guard let entry = sessions[handle] else {
                lock.unlock()
                print("[OnnxBridge] runArgmaxLastStep: invalid handle \(handle)")
                return -1
            }
            let session = entry.session
            let persistentInt64 = entry.persistentInt64
            let persistentFloat = entry.persistentFloat
            lock.unlock()

            do {
                var inputs: [String: ORTValue] = [:]
                var transientBacking: [NSMutableData] = []
                inputs.reserveCapacity(persistentInt64.count + persistentFloat.count + Int(int64Names.size))
                transientBacking.reserveCapacity(Int(int64Names.size))
                for (k, v) in persistentInt64 { inputs[k] = v }
                for (k, v) in persistentFloat { inputs[k] = v }

                let count = Int(int64Names.size)
                for i in 0..<count {
                    let name = int64Names.get(index: Int32(i))! as String
                    let arr = int64Data.get(index: Int32(i))!
                    let shape = int64Shapes.get(index: Int32(i))!
                    let (value, backing) = try buildInt64Tensor(array: arr, shape: shape)
                    inputs[name] = value
                    transientBacking.append(backing)
                }

                let outSet: Set<String> = [logitsOutputName]
                let outputs = try withExtendedLifetime(transientBacking) {
                    try session.run(withInputs: inputs, outputNames: outSet, runOptions: nil)
                }
                guard let logitsValue = outputs[logitsOutputName] else {
                    print("[OnnxBridge] runArgmaxLastStep: missing output \(logitsOutputName)")
                    return -1
                }

                let info = try logitsValue.tensorTypeAndShapeInfo()
                let shape = info.shape.map { Int(truncating: $0) }
                let vocab = shape.last ?? Int(vocabSize)
                let outputSeqLen = shape.count >= 2 ? shape[shape.count - 2] : Int(lastStepIndex) + 1
                let step = Int(lastStepIndex)
                guard vocab > 0, step >= 0, step < outputSeqLen else {
                    print("[OnnxBridge] runArgmaxLastStep: invalid logits shape \(shape), step=\(step)")
                    return -1
                }

                let data = try logitsValue.tensorData() as Data
                let lastStart = step * vocab
                let byteOffset = lastStart * MemoryLayout<Float>.size
                let byteLen = vocab * MemoryLayout<Float>.size
                guard byteOffset + byteLen <= data.count else {
                    print("[OnnxBridge] runArgmaxLastStep: slice out of range " +
                          "(offset=\(byteOffset) len=\(byteLen) total=\(data.count) shape=\(shape))")
                    return -1
                }

                var bestIdx: Int32 = 0
                var bestVal: Float = -.infinity
                data.withUnsafeBytes { (raw: UnsafeRawBufferPointer) in
                    let base = raw.baseAddress!.advanced(by: byteOffset)
                        .assumingMemoryBound(to: Float.self)
                    for i in 0..<vocab {
                        let v = base[i]
                        if v > bestVal { bestVal = v; bestIdx = Int32(i) }
                    }
                }
                return bestIdx
            } catch {
                print("[OnnxBridge] runArgmaxLastStep error: \(error)")
                return -1
            }
        }
        #else
        return -1
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
        return autoreleasepool { () -> KotlinArray<OnnxOutput> in
            lock.lock()
            guard let entry = sessions[handle] else {
                lock.unlock()
                print("[OnnxBridge] run: invalid handle \(handle)")
                return KotlinArray<OnnxOutput>(size: 0) { _ in OnnxOutput(data: KotlinFloatArray(size: 0), shape: KotlinLongArray(size: 0)) }
            }
            let session = entry.session
            let persistentInt64 = entry.persistentInt64
            let persistentFloat = entry.persistentFloat
            lock.unlock()

            do {
                var inputs: [String: ORTValue] = [:]
                var transientBacking: [NSMutableData] = []
                let int64Count = Int(int64Names.size)
                let floatCount = Int(floatNames.size)
                inputs.reserveCapacity(persistentInt64.count + persistentFloat.count + int64Count + floatCount)
                transientBacking.reserveCapacity(int64Count + floatCount)
                for (k, v) in persistentInt64 { inputs[k] = v }
                for (k, v) in persistentFloat { inputs[k] = v }

                for i in 0..<int64Count {
                    let name = int64Names.get(index: Int32(i))! as String
                    let arr = int64Data.get(index: Int32(i))!
                    let shape = int64Shapes.get(index: Int32(i))!
                    let (value, backing) = try buildInt64Tensor(array: arr, shape: shape)
                    inputs[name] = value
                    transientBacking.append(backing)
                }
                for i in 0..<floatCount {
                    let name = floatNames.get(index: Int32(i))! as String
                    let arr = floatData.get(index: Int32(i))!
                    let shape = floatShapes.get(index: Int32(i))!
                    let (value, backing) = try buildFloatTensor(array: arr, shape: shape)
                    inputs[name] = value
                    transientBacking.append(backing)
                }

                let outNames: [String] = (0..<Int(outputNames.size)).map { outputNames.get(index: Int32($0))! as String }
                let outSet = Set(outNames)
                let outputsDict = try withExtendedLifetime(transientBacking) {
                    try session.run(
                        withInputs: inputs,
                        outputNames: outSet,
                        runOptions: nil
                    )
                }

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
                        if count > 0 {
                            data.withUnsafeBytes { (raw: UnsafeRawBufferPointer) in
                                let src = raw.bindMemory(to: Float.self).baseAddress!
                                for j in 0..<count { kotlinFloats.set(index: Int32(j), value: src[j]) }
                            }
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
        }
        #else
        return KotlinArray<OnnxOutput>(size: 0) { _ in OnnxOutput(data: KotlinFloatArray(size: 0), shape: KotlinLongArray(size: 0)) }
        #endif
    }

    #if canImport(OnnxRuntimeBindings)
    private func buildInt64Tensor(array: KotlinLongArray, shape: KotlinLongArray) throws -> (ORTValue, NSMutableData) {
        let count = Int(array.size)
        let buf = NSMutableData(length: count * MemoryLayout<Int64>.size)!
        let dst = buf.mutableBytes.bindMemory(to: Int64.self, capacity: count)
        for i in 0..<count { dst[i] = array.get(index: Int32(i)) }
        let shapeArr: [NSNumber] = (0..<Int(shape.size)).map { NSNumber(value: shape.get(index: Int32($0))) }
        let value = try ORTValue(tensorData: buf, elementType: .int64, shape: shapeArr)
        return (value, buf)
    }

    private func buildFloatTensor(array: KotlinFloatArray, shape: KotlinLongArray) throws -> (ORTValue, NSMutableData) {
        let count = Int(array.size)
        let buf = NSMutableData(length: count * MemoryLayout<Float>.size)!
        let dst = buf.mutableBytes.bindMemory(to: Float.self, capacity: count)
        for i in 0..<count { dst[i] = array.get(index: Int32(i)) }
        let shapeArr: [NSNumber] = (0..<Int(shape.size)).map { NSNumber(value: shape.get(index: Int32($0))) }
        let value = try ORTValue(tensorData: buf, elementType: .float, shape: shapeArr)
        return (value, buf)
    }
    #endif
}
