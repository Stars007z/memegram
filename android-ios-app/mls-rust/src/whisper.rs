use std::sync::{Arc, Mutex};

use rubato::{
    Resampler, SincFixedIn, SincInterpolationParameters, SincInterpolationType, WindowFunction,
};
use symphonia::core::audio::SampleBuffer;
use symphonia::core::codecs::DecoderOptions;
use symphonia::core::errors::Error as SymphoniaError;
use symphonia::core::formats::FormatOptions;
use symphonia::core::io::MediaSourceStream;
use symphonia::core::meta::MetadataOptions;
use symphonia::core::probe::Hint;
use whisper_rs::{
    FullParams, SamplingStrategy, WhisperContext, WhisperContextParameters, WhisperState,
};

use crate::MlsError;

const WHISPER_SAMPLE_RATE: u32 = 16_000;

#[derive(uniffi::Record)]
pub struct WhisperTranscription {
    pub text: String,
    pub language: String,
}

#[uniffi::export(callback_interface)]
pub trait WhisperProgressCallback: Send + Sync {
    fn on_progress(&self, progress: i32);
}

#[derive(uniffi::Object)]
pub struct WhisperEngine {
    model_path: String,
    ctx: Mutex<Option<WhisperContext>>,
}

#[uniffi::export]
impl WhisperEngine {
    #[uniffi::constructor]
    pub fn new(model_path: String) -> Result<Arc<Self>, MlsError> {
        let params = WhisperContextParameters::default();
        let ctx = WhisperContext::new_with_params(&model_path, params).map_err(|e| {
            MlsError::General(format!("whisper load failed: {model_path}: {e}"))
        })?;
        Ok(Arc::new(Self {
            model_path,
            ctx: Mutex::new(Some(ctx)),
        }))
    }

    pub fn transcribe(
        &self,
        audio_bytes: Vec<u8>,
        language: Option<String>,
        progress: Option<Box<dyn WhisperProgressCallback>>,
    ) -> Result<WhisperTranscription, MlsError> {
        let pcm = decode_to_pcm_16k_mono(&audio_bytes)?;

        let mut guard = self.ctx.lock().map_err(|_| {
            MlsError::General("whisper context mutex poisoned".into())
        })?;
        let ctx = guard.as_mut().ok_or_else(|| {
            MlsError::General("whisper engine has been released".into())
        })?;

        let mut state: WhisperState = ctx.create_state().map_err(|e| {
            MlsError::General(format!("whisper create_state: {e}"))
        })?;

        let mut params = FullParams::new(SamplingStrategy::Greedy { best_of: 1 });
        params.set_print_special(false);
        params.set_print_progress(false);
        params.set_print_realtime(false);
        params.set_print_timestamps(false);
        params.set_translate(false);
        params.set_no_context(true);
        params.set_no_timestamps(true);
        params.set_single_segment(true);
        let duration_ms = pcm_duration_ms(pcm.len());
        params.set_duration_ms(duration_ms);
        if let Some(ref lang) = language {
            params.set_language(Some(lang.as_str()));
        } else {
            params.set_language(Some("auto"));
        }
        params.set_n_threads(num_threads());

        if let Some(cb) = progress {
            let cb: Arc<dyn WhisperProgressCallback> = Arc::from(cb);
            params.set_progress_callback_safe(move |p: i32| {
                cb.on_progress(p);
            });
        }

        state
            .full(params, &pcm)
            .map_err(|e| MlsError::General(format!("whisper full: {e}")))?;

        let n_segments = state
            .full_n_segments()
            .map_err(|e| MlsError::General(format!("whisper full_n_segments: {e}")))?;

        let mut out = String::new();
        for i in 0..n_segments {
            let seg = state
                .full_get_segment_text(i)
                .map_err(|e| MlsError::General(format!("whisper segment {i}: {e}")))?;
            if !out.is_empty() {
                out.push(' ');
            }
            out.push_str(seg.trim());
        }

        let detected = if let Some(lang) = language {
            lang
        } else {
            state
                .full_lang_id_from_state()
                .ok()
                .and_then(|id| whisper_rs::get_lang_str(id).map(|s: &str| s.to_string()))
                .unwrap_or_else(|| "en".to_string())
        };

        Ok(WhisperTranscription {
            text: out,
            language: detected,
        })
    }

    pub fn release(&self) {
        if let Ok(mut guard) = self.ctx.lock() {
            let _ = guard.take();
        }
    }

    pub fn model_path(&self) -> String {
        self.model_path.clone()
    }
}

fn num_threads() -> std::os::raw::c_int {
    let cores = std::thread::available_parallelism()
        .map(|n| n.get())
        .unwrap_or(4);
    cores.clamp(2, 4) as std::os::raw::c_int
}

fn pcm_duration_ms(sample_count: usize) -> std::os::raw::c_int {
    let duration = (sample_count as u64 * 1000 / WHISPER_SAMPLE_RATE as u64) as i32;
    duration.clamp(1_000, 30_000) as std::os::raw::c_int
}

fn decode_to_pcm_16k_mono(audio_bytes: &[u8]) -> Result<Vec<f32>, MlsError> {
    let cursor = std::io::Cursor::new(audio_bytes.to_vec());
    let mss = MediaSourceStream::new(Box::new(cursor), Default::default());

    let hint = Hint::new();
    let format_opts = FormatOptions::default();
    let metadata_opts = MetadataOptions::default();

    let probed = symphonia::default::get_probe()
        .format(&hint, mss, &format_opts, &metadata_opts)
        .map_err(|e| MlsError::General(format!("audio probe: {e}")))?;

    let mut format = probed.format;
    let track = format
        .default_track()
        .ok_or_else(|| MlsError::General("no default audio track".into()))?;
    let track_id = track.id;
    let codec_params = track.codec_params.clone();
    let source_rate = codec_params
        .sample_rate
        .ok_or_else(|| MlsError::General("audio: unknown sample rate".into()))?;
    let mut decoder = symphonia::default::get_codecs()
        .make(&codec_params, &DecoderOptions::default())
        .map_err(|e| MlsError::General(format!("audio decoder: {e}")))?;

    let mut interleaved: Vec<f32> = Vec::with_capacity(audio_bytes.len() / 2);
    let mut sample_buf: Option<SampleBuffer<f32>> = None;
    let mut n_channels: Option<usize> = None;

    loop {
        let packet = match format.next_packet() {
            Ok(p) => p,
            Err(SymphoniaError::IoError(ref e))
                if e.kind() == std::io::ErrorKind::UnexpectedEof =>
            {
                break;
            }
            Err(SymphoniaError::ResetRequired) => break,
            Err(e) => return Err(MlsError::General(format!("audio packet: {e}"))),
        };

        if packet.track_id() != track_id {
            continue;
        }

        match decoder.decode(&packet) {
            Ok(decoded) => {
                if sample_buf.is_none() {
                    let spec = *decoded.spec();
                    n_channels = Some(spec.channels.count());
                    let duration = decoded.capacity() as u64;
                    sample_buf = Some(SampleBuffer::<f32>::new(duration, spec));
                }
                if let Some(buf) = sample_buf.as_mut() {
                    buf.copy_interleaved_ref(decoded);
                    interleaved.extend_from_slice(buf.samples());
                }
            }
            Err(SymphoniaError::DecodeError(_)) => continue,
            Err(e) => return Err(MlsError::General(format!("audio decode: {e}"))),
        }
    }

    if interleaved.is_empty() {
        return Err(MlsError::General("audio decoded to 0 samples".into()));
    }

    let n_channels = n_channels
        .filter(|count| *count > 0)
        .or_else(|| codec_params.channels.map(|channels| channels.count()))
        .filter(|count| *count > 0)
        .unwrap_or(1);

    let mono: Vec<f32> = if n_channels == 1 {
        interleaved
    } else {
        let frames = interleaved.len() / n_channels;
        let mut out = Vec::with_capacity(frames);
        for f in 0..frames {
            let mut sum = 0.0_f32;
            for c in 0..n_channels {
                sum += interleaved[f * n_channels + c];
            }
            out.push(sum / n_channels as f32);
        }
        out
    };

    if source_rate == WHISPER_SAMPLE_RATE {
        return Ok(mono);
    }

    resample_to_16k(&mono, source_rate)
}

fn resample_to_16k(input: &[f32], source_rate: u32) -> Result<Vec<f32>, MlsError> {
    let params = SincInterpolationParameters {
        sinc_len: 128,
        f_cutoff: 0.95,
        interpolation: SincInterpolationType::Linear,
        oversampling_factor: 128,
        window: WindowFunction::BlackmanHarris2,
    };

    let chunk = 1024;
    let mut resampler =
        SincFixedIn::<f32>::new(WHISPER_SAMPLE_RATE as f64 / source_rate as f64, 2.0, params, chunk, 1)
            .map_err(|e| MlsError::General(format!("rubato init: {e}")))?;

    let mut out: Vec<f32> = Vec::with_capacity(
        (input.len() as u64 * WHISPER_SAMPLE_RATE as u64 / source_rate as u64) as usize + 1024,
    );

    let mut pos = 0usize;
    let mut pad_buf: Vec<f32>;
    while pos < input.len() {
        let end = (pos + chunk).min(input.len());
        let slice: &[f32] = if end - pos == chunk {
            &input[pos..end]
        } else {
            pad_buf = vec![0.0_f32; chunk];
            pad_buf[..(end - pos)].copy_from_slice(&input[pos..end]);
            &pad_buf
        };

        let waves_in: Vec<&[f32]> = vec![slice];
        let waves_out = resampler
            .process(&waves_in, None)
            .map_err(|e| MlsError::General(format!("rubato process: {e}")))?;
        if let Some(channel0) = waves_out.into_iter().next() {
            out.extend(channel0);
        }
        pos = end;
    }

    Ok(out)
}
