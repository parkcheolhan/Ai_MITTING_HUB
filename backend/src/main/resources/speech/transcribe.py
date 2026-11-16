"""Local audio/video transcription. Outputs timestamps; never invents speaker identities."""
import argparse
import json
import os
from pathlib import Path


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--model-dir", required=True)
    parser.add_argument("--prepare", action="store_true")
    parser.add_argument("--check-speakers", action="store_true")
    parser.add_argument("--input")
    parser.add_argument("--output")
    parser.add_argument("--language", default="ko")
    parser.add_argument("--speaker-dir")
    parser.add_argument("--max-speakers",type=int,default=0)
    parser.add_argument("--segments")
    args = parser.parse_args()
    try:
        if args.check_speakers:
            from diarize import load_engine
            engine = load_engine(args.speaker_dir)
            print(json.dumps({"speakerReady": True, "sampleRate": engine.sample_rate}))
            return
        if args.prepare:
            from faster_whisper.utils import download_model
            download_model("small", output_dir=args.model_dir)
            if args.speaker_dir:
                from diarize import prepare
                prepare(args.speaker_dir)
            print("Speech model ready")
            return
        if not Path(args.model_dir, "model.bin").is_file():
            raise ValueError("MODEL_MISSING")
        import av
        with av.open(args.input) as container:
            audio = next(iter(container.streams.audio), None)
            if audio is None:
                raise ValueError("NO_AUDIO")
            duration = (audio.duration * audio.time_base if audio.duration is not None else
                        container.duration / av.time_base if container.duration else 0)
            if duration > 4 * 3600:
                raise ValueError("TOO_LONG")
        from faster_whisper import WhisperModel
        from faster_whisper.audio import decode_audio
        samples = decode_audio(args.input, sampling_rate=16000)
        if args.segments:
            from diarize import identify
            result = identify(samples, json.loads(Path(args.segments).read_text(encoding="utf-8")), args.speaker_dir,args.max_speakers or None)
            Path(args.output).write_text(json.dumps({"segments": result}, ensure_ascii=False), encoding="utf-8")
            return
        model = WhisperModel(args.model_dir, device="cpu", compute_type="int8",
                             cpu_threads=min(4, os.cpu_count() or 2), local_files_only=True)
        segments, info = model.transcribe(samples, language=args.language if args.language != "auto" else None,
                                         beam_size=5, vad_filter=True, condition_on_previous_text=False,
                                         vad_parameters={"min_silence_duration_ms": 500})
        result = []
        text_size = 0
        for segment in segments:
            text = segment.text.strip()
            if not text:
                continue
            text_size += len(text)
            if len(result) >= 3000 or text_size > 200000:
                raise ValueError("TOO_LONG")
            result.append({"start": segment.start, "end": segment.end, "text": text})
        if not result:
            raise ValueError("NO_SPEECH")
        if not args.speaker_dir:
            raise ValueError("SPEAKER_MODEL_MISSING")
        from diarize import identify
        result = identify(samples, result, args.speaker_dir,args.max_speakers or None)
        Path(args.output).write_text(json.dumps({"segments": result, "language": info.language}, ensure_ascii=False), encoding="utf-8")
    except Exception as error:
        codes = {"MODEL_MISSING", "NO_AUDIO", "TOO_LONG", "NO_SPEECH", "SPEAKER_MODEL_MISSING"}
        code = str(error) if str(error) in codes else "INVALID_MEDIA"
        if args.output:
            Path(args.output).write_text(json.dumps({"error": code}), encoding="utf-8")
        raise SystemExit(1)


if __name__ == "__main__":
    main()
