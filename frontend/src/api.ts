import { standalone, localApi } from './standalone';
import {apiUrl,authHeaders} from './remote';
export async function api<T>(
  path: string,
  method = "GET",
  body?: unknown,
): Promise<T> {
  if (standalone) return localApi<T>(path, method, body);
  const response = await fetch(apiUrl(path), {
    method,
    headers: {...authHeaders(),...(body === undefined ? {} : { "Content-Type": "application/json" })},
    body: body === undefined ? undefined : JSON.stringify(body),
  });
  if (!response.ok) {
    const error = await response
      .json()
      .catch(() => ({ message: "서버에 연결할 수 없습니다." }));
    throw new Error(
      error.message || error.detail || "요청 처리에 실패했습니다.",
    );
  }
  const text = await response.text();
  return (text ? JSON.parse(text) : undefined) as T;
}
export async function upload(
  id: string,
  file: File,
  onProgress: (value: number) => void,
  method: 'POST'|'PUT' = 'POST',
): Promise<void> {
  if (standalone) throw new Error('HTML 로컬 버전은 음성·영상 업로드를 지원하지 않습니다. TXT/SRT/VTT 전사문을 등록하세요.');
  // Snapshot the selected file before sending. Synced files can change between
  // selection and upload, which Chromium reports as a generic network error.
  let contents: ArrayBuffer;
  try {
    contents = await file.arrayBuffer();
  } catch {
    throw new Error("선택한 파일을 읽을 수 없습니다. OneDrive 파일은 ‘이 장치에 항상 유지’를 선택하거나 로컬 폴더에 복사한 뒤 다시 선택하세요.");
  }
  if (contents.byteLength !== file.size || contents.byteLength === 0)
    throw new Error("선택한 파일이 변경되었거나 비어 있습니다. 파일을 다시 선택하세요.");
  const snapshot = new Blob([contents], { type: file.type });
  return new Promise((resolve, reject) => {
    const xhr = new XMLHttpRequest();
    xhr.open(method, apiUrl(`/meetings/${id}/assets`));
    for(const [name,value] of Object.entries(authHeaders()))xhr.setRequestHeader(name,value);
    xhr.upload.onprogress = (e) => {
      if (e.lengthComputable)
        onProgress(Math.round((e.loaded / e.total) * 100));
    };
    xhr.onerror = () =>
      reject(new Error("파일 업로드 연결이 끊겼습니다. 서버 실행 상태를 확인한 뒤 분석 재시도를 눌러주세요. 저장된 회의에서 계속 진행합니다."));
    xhr.timeout = 10 * 60 * 1000;
    xhr.ontimeout = () => reject(new Error("파일 전송 시간이 10분을 초과했습니다. 연결 상태를 확인한 뒤 다시 시도하세요."));
    xhr.onabort = () => reject(new Error("파일 전송이 중단되었습니다. 다시 시도하세요."));
    xhr.onload = () => {
      if (xhr.status >= 200 && xhr.status < 300) resolve();
      else {
        try {
          const error = JSON.parse(xhr.responseText);
          reject(new Error(error.message || error.detail || `파일 업로드에 실패했습니다. (HTTP ${xhr.status})`));
        } catch {
          reject(new Error(xhr.status === 413 ? "파일 크기는 100MB 이하여야 합니다." : `파일 업로드에 실패했습니다. (HTTP ${xhr.status}) 서버 실행 상태를 확인하세요.`));
        }
      }
    };
    const form = new FormData();
    form.append("file", snapshot, file.name);
    xhr.send(form);
  });
}
