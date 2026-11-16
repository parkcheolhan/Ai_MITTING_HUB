"""Local speaker segmentation and clustering. Labels are anonymous, never names."""
from pathlib import Path

# Prefer merging similar voices over splitting one person across many labels.
# sherpa-onnx: a larger distance threshold produces fewer speaker clusters.
SPEAKER_CLUSTER_THRESHOLD = 1.0

def identify(audio, segments, model_dir):
    import sherpa_onnx
    model_dir = Path(model_dir)
    config = sherpa_onnx.OfflineSpeakerDiarizationConfig(
        segmentation=sherpa_onnx.OfflineSpeakerSegmentationModelConfig(
            pyannote=sherpa_onnx.OfflineSpeakerSegmentationPyannoteModelConfig(
                model=str(model_dir / "segmentation.onnx")), num_threads=2),
        embedding=sherpa_onnx.SpeakerEmbeddingExtractorConfig(
            model=str(model_dir / "embedding.onnx"), num_threads=2),
        clustering=sherpa_onnx.FastClusteringConfig(num_clusters=-1, threshold=SPEAKER_CLUSTER_THRESHOLD),
        min_duration_on=0.3, min_duration_off=0.5)
    if not config.validate():
        raise ValueError("SPEAKER_MODEL_MISSING")
    engine = sherpa_onnx.OfflineSpeakerDiarization(config)
    turns = engine.process(audio).sort_by_start_time()
    labels = {}
    for segment in segments:
        overlaps = {}
        for turn in turns:
            length = max(0.0, min(segment["end"], turn.end) - max(segment["start"], turn.start))
            if length:
                overlaps[turn.speaker] = overlaps.get(turn.speaker, 0.0) + length
        if overlaps:
            speaker = max(overlaps, key=overlaps.get)
            if speaker not in labels:
                labels[speaker] = f"화자 {len(labels) + 1}"
            segment["speaker"] = labels[speaker]
        else:
            segment["speaker"] = "화자 미지정"
    return segments


def prepare(model_dir):
    import tarfile
    import urllib.request
    model_dir = Path(model_dir)
    model_dir.mkdir(parents=True, exist_ok=True)
    base = "https://github.com/k2-fsa/sherpa-onnx/releases/download/"
    if not (model_dir / "segmentation.onnx").is_file():
        archive = model_dir / "segmentation.tar.bz2"
        urllib.request.urlretrieve(base + "speaker-segmentation-models/sherpa-onnx-pyannote-segmentation-3-0.tar.bz2", archive)
        with tarfile.open(archive) as files:
            member = next(m for m in files.getmembers() if m.name.endswith("/model.int8.onnx"))
            (model_dir / "segmentation.onnx").write_bytes(files.extractfile(member).read())
    if not (model_dir / "embedding.onnx").is_file():
        urllib.request.urlretrieve(base + "speaker-recongition-models/3dspeaker_speech_eres2net_base_sv_zh-cn_3dspeaker_16k.onnx", model_dir / "embedding.onnx")
