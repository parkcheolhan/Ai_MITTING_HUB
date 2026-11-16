"""Local speaker segmentation and clustering. Labels are anonymous, never names."""
from pathlib import Path
from contextlib import chdir

# Prefer merging similar voices over splitting one person across many labels.
# sherpa-onnx: a larger distance threshold produces fewer speaker clusters.
SPEAKER_CLUSTER_THRESHOLD = 1.0

def load_engine(model_dir):
    import sherpa_onnx
    model_dir = Path(model_dir).resolve()
    if not all((model_dir / name).is_file() for name in ("segmentation.onnx", "embedding.onnx")):
        raise ValueError("SPEAKER_MODEL_MISSING")
    # sherpa-onnx on Windows cannot open Unicode absolute paths. Python can
    # enter that directory; ASCII filenames then work, including across drives.
    # Each Java speech job runs this in its own Python process.
    with chdir(model_dir):
        config = sherpa_onnx.OfflineSpeakerDiarizationConfig(
            segmentation=sherpa_onnx.OfflineSpeakerSegmentationModelConfig(
                pyannote=sherpa_onnx.OfflineSpeakerSegmentationPyannoteModelConfig(
                    model="segmentation.onnx"), num_threads=2),
            embedding=sherpa_onnx.SpeakerEmbeddingExtractorConfig(
                model="embedding.onnx", num_threads=2),
            clustering=sherpa_onnx.FastClusteringConfig(num_clusters=-1, threshold=SPEAKER_CLUSTER_THRESHOLD),
            min_duration_on=0.3, min_duration_off=0.5)
        if not config.validate():
            raise ValueError("SPEAKER_MODEL_MISSING")
        return sherpa_onnx.OfflineSpeakerDiarization(config)


def identify(audio, segments, model_dir, max_speakers=None):
    engine = load_engine(model_dir)
    turns = engine.process(audio).sort_by_start_time()
    import sherpa_onnx
    import numpy as np
    with chdir(Path(model_dir).resolve()):
        extractor = sherpa_onnx.SpeakerEmbeddingExtractor(
            sherpa_onnx.SpeakerEmbeddingExtractorConfig(model="embedding.onnx", num_threads=2))
    cache = {}
    def embedding(start, end):
        key = (start, end)
        if key in cache:
            return cache[key]
        begin = max(0, int(start * 16000))
        finish = min(len(audio), int(end * 16000), begin + 128000)
        samples = np.asarray(audio[begin:finish], dtype=np.float32)
        value = None
        if len(samples) >= 4000 and float(np.sqrt(np.mean(samples * samples))) > 0.0001:
            stream = extractor.create_stream()
            stream.accept_waveform(sample_rate=16000, waveform=samples)
            stream.input_finished()
            if extractor.is_ready(stream):
                value = extractor.compute(stream)
        cache[key] = value
        return value
    return assign_speakers(segments, turns, embedding, max_speakers)


def unit_vector(value):
    import numpy as np
    if value is None:
        return None
    vector = np.asarray(value, dtype=np.float32)
    length = float(np.linalg.norm(vector))
    return vector / length if length > 0 and np.isfinite(vector).all() else None


def assign_speakers(segments, turns, embedding, max_speakers=None):
    """Prefer diarization evidence; fill gaps by voice similarity, never by text."""
    import numpy as np
    references = {}
    mapping = {turn.speaker: turn.speaker for turn in turns}
    weights = {speaker: sum(t.end-t.start for t in turns if t.speaker == speaker) for speaker in mapping}
    for speaker in sorted({turn.speaker for turn in turns}):
        samples = sorted((t for t in turns if t.speaker == speaker), key=lambda t: t.end-t.start, reverse=True)[:3]
        vectors = [v for t in samples if (v := unit_vector(embedding(t.start, t.end))) is not None]
        if vectors:
            references[speaker] = unit_vector(np.mean(vectors, axis=0))
    # Agglomerate the closest acoustic prototypes until the configured upper
    # bound is respected. This is an upper bound, not a forced speaker count.
    if max_speakers:
        while len(set(mapping.values())) > max_speakers:
            ids = sorted(set(mapping.values()))
            pairs = [(float(np.dot(references[a], references[b])), a, b) for i,a in enumerate(ids) for b in ids[i+1:] if a in references and b in references]
            if pairs:
                _,a,b = max(pairs)
            else:
                a,b = sorted(ids,key=lambda k:weights.get(k,0))[:2]
            if a in references and b in references:
                combined = unit_vector(references[a]*weights.get(a,1)+references[b]*weights.get(b,1))
                if combined is not None:
                    references[a] = combined
            elif b in references:
                references[a] = references[b]
            references.pop(b,None)
            weights[a] = weights.get(a,0)+weights.get(b,0)
            mapping = {key:a if value==b else value for key,value in mapping.items()}
    next_speaker = max([t.speaker for t in turns] + [-1]) + 1
    labels = {}
    for segment in segments:
        overlaps = {}
        for turn in turns:
            length = max(0.0, min(segment["end"], turn.end) - max(segment["start"], turn.start))
            if length:
                overlaps[turn.speaker] = overlaps.get(turn.speaker, 0.0) + length
        duration = max(0, segment["end"]-segment["start"])
        matched = max(overlaps, key=overlaps.get) if overlaps else None
        similarity = None
        if matched is not None and overlaps[matched] >= max(0.12, duration*0.25):
            speaker, method = mapping[matched], "DIARIZATION"
        else:
            vector = unit_vector(embedding(segment["start"], segment["end"]))
            scores = sorted(((float(np.dot(vector, reference)), speaker) for speaker, reference in references.items()), reverse=True) if vector is not None else []
            if scores:
                similarity, speaker = scores[0]
                margin = similarity-scores[1][0] if len(scores)>1 else 1.0
                method = "VOICE_MATCH" if similarity >= 0.45 and margin >= 0.08 and duration >= 0.6 else "ESTIMATED"
                if similarity < 0.15 and duration >= 1.5 and (not max_speakers or len(set(mapping.values())|set(references))<max_speakers):
                    speaker, method = next_speaker, "NEW_VOICE"
                    next_speaker += 1
                    references[speaker] = vector
            elif vector is not None:
                existing = set(mapping.values()) | set(references) | set(labels)
                if max_speakers and len(existing) >= max_speakers:
                    speaker, method = min(existing), "VOICE_MATCH"
                else:
                    speaker, method = next_speaker, "NEW_VOICE"
                    next_speaker += 1
                references[speaker] = vector
            else:
                # No reliable acoustic features: retain a numbered provisional
                # match, explicitly marked estimated rather than claiming certainty.
                nearest = min(turns, key=lambda t: min(abs(t.start-segment["end"]), abs(t.end-segment["start"]))) if turns else None
                speaker, method = (mapping.get(nearest.speaker,nearest.speaker) if nearest else 0), "ESTIMATED"
        if speaker not in labels:
            labels[speaker] = f"화자 {len(labels) + 1}"
        segment["speaker"] = labels[speaker]
        segment["speakerAssignment"] = method
        segment["speakerSimilarity"] = None if similarity is None else round(similarity, 4)
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
