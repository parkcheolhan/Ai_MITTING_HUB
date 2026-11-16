export async function readTranscript(file: File): Promise<string> {
  if (!/\.(txt|srt|vtt)$/i.test(file.name))
    throw new Error("음성 기록은 TXT, SRT, VTT 파일로 가져오세요.");
  if (!file.size || file.size > 1024 * 1024)
    throw new Error("음성 기록 파일은 1MB 이하로 선택하세요.");
  const bytes = new Uint8Array(await file.arrayBuffer());
  let text: string;
  if (bytes[0] === 0xff && bytes[1] === 0xfe)
    text = new TextDecoder("utf-16le").decode(bytes);
  else if (bytes[0] === 0xfe && bytes[1] === 0xff)
    text = new TextDecoder("utf-16be").decode(bytes);
  else {
    try {
      text = new TextDecoder("utf-8", { fatal: true }).decode(bytes);
    } catch {
      text = new TextDecoder("euc-kr", { fatal: true }).decode(bytes);
    }
  }
  text = text.replace(/^\uFEFF/, "");
  if (text.includes("\0") || !text.trim())
    throw new Error(
      "텍스트 내용을 읽을 수 없습니다. UTF-8 TXT 파일로 저장해 주세요.",
    );
  if (text.length > 200000)
    throw new Error("음성 기록은 20만 자 이하로 나누어 등록하세요.");
  return text;
}
