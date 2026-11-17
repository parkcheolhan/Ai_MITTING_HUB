# AI Meeting Hub · GitHub Pages + 내 PC 서버

GitHub Pages: https://parkcheolhan.github.io/Ai_MITTING_HUB/

저장소: https://github.com/parkcheolhan/Ai_MITTING_HUB

화면은 GitHub Pages에서 제공하고, 회의·첨부파일·AI 분석·음성 변환·Jira 연결은 내 PC의 Java 서버에서 처리합니다. 서버 데이터는 GitHub에 저장되지 않습니다. 앞서 만든 브라우저 저장용 standalone HTML과 다른 **서버 연결 버전**입니다.

[음성 기록 편집·같은 화자 묶음 표시·AI 문맥 교정 사용 방법](transcript-review.md)

## GitHub 업로드 및 Pages 활성화

준비된 `github` 폴더의 **내용물**을 저장소 최상위에 올리세요. `github` 폴더 자체를 최상위 폴더로 올리지 마세요. `index.html`, `frontend`, `backend`, `scripts`, `.github/workflows/pages.yml`, `.nojekyll` 등이 최상위에 있어야 합니다. 숨김 파일도 포함하세요.

저장소 Settings → Pages → Build and deployment → Source를 **GitHub Actions**로 선택합니다. `main` 브랜치에 올리면 포함된 workflow가 프론트엔드를 빌드하고 배포합니다. 기존 파일을 수동 배포하려면 Source에서 Deploy from a branch → main → /(root)를 선택해도 됩니다. 이후 소스 수정은 Actions 방식에서 자동 반영됩니다.

소스 업로드만으로 Java 서버가 GitHub에서 실행되지는 않습니다. 서버·터널은 아래와 같이 내 PC에서 실행합니다.

## Windows 로컬 서버 시작

JDK 17 이상과 PowerShell이 필요합니다. 소스를 새로 받은 PC에서는 JDK를 설치하고 JAVA_HOME을 지정하세요. 음성 전사 모델은 이 저장소에 포함되지 않습니다. 기존 PC에 설치된 엔진·모델은 계속 사용합니다. 새 PC는 `scripts/setup-speech.ps1`을 먼저 실행하세요.

프로젝트 루트에서 실행:

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File .\scripts\start-remote.ps1 -Build
```

실행 스크립트는 다음을 처리합니다.

- 백엔드 빌드와 `demo,remote` 프로필 실행, 기본 127.0.0.1:8080 바인딩
- 기존 `backend/data/demo` H2 DB·첨부·암호화 설정 사용 (새 PC는 빈 저장소)
- 무작위 접속 토큰 생성: `.local-remote/access-token.txt` (기존 토큰 재사용)
- 공식 cloudflared 다운로드 시 SHA256 검증 후 임시 HTTPS 터널 실행
- 허용 출처를 `https://parkcheolhan.github.io`로 제한
- API 주소 저장: `.local-remote/connection.json`

기존 Java 서버가 같은 포트·DB를 사용 중이면 먼저 기존 서버를 종료하세요. `-Port 8082`로 포트만 바꿔도 같은 DB의 중복 실행은 허용되지 않습니다. 원격 모드에서는 localhost 요청도 접속 토큰이 필요합니다. 서버 설정 변경으로 재시작할 때는 `stop-remote.ps1` 후 다시 시작하세요.

임시 터널은 재시작마다 API 주소가 바뀝니다. 고정 주소를 쓰려면 Cloudflare 계정의 고정 도메인 터널을 별도로 만들고 `start-remote.ps1 -NoTunnel`로 서버만 실행한 뒤 localhost:8080에 연결하세요. Quick Tunnel은 테스트용이며 가용성 보장이 없습니다.

## Pages 화면에서 연결

Pages 주소를 열고 API 서버 HTTPS 주소에는 `.local-remote/connection.json`의 `apiUrl`을, 접속 토큰에는 `.local-remote/access-token.txt` 내용을 입력합니다. 토큰은 해당 탭의 sessionStorage에만 보관하며 URL·GitHub 소스·API 쿼리에는 넣지 않습니다. 접속 토큰을 가진 사람은 이 서버의 회의·AI·Jira 설정에 접근하는 공유 관리자입니다. 사람별 계정 시스템은 아닙니다.

연결 후 설정에서 AI·Jira 연결 상태를 확인하고 필요한 인증 정보를 저장합니다. 기존 로컬 DB의 설정은 그대로 사용합니다. AI 호출은 사용자의 계정/API 사용량을 사용하며 실제 Jira 등록은 기존 검토·승인 흐름으로 실행됩니다. 코드 배포가 실제 AI/Jira 연결 성공을 보장하지는 않습니다.

음성·영상 재생은 Authorization 헤더로 파일을 받아 메모리의 Blob URL로 재생합니다. 토큰이 링크·URL에 노출되지 않지만 큰 파일은 다운로드 후 재생 준비에 시간이 걸리며 브라우저 메모리를 사용합니다. Cloudflare 등 터널 사업자의 업로드 한도는 앱의 100MB 한도보다 낮게 적용될 수 있습니다. 100MB에 가까운 파일은 분할하거나 로컬에서 업로드하세요.

PC가 꺼지거나 절전·인터넷 연결 해제·서버 또는 터널 종료 상태가 되면 Pages 화면은 열려도 서버 기능은 사용할 수 없습니다.

## 에픽 조회와 티켓 배치

회의의 **JIRA 검토 → 에픽 조회·추가**에서 회의 프로젝트의 에픽을 제목이나 Jira 키로 검색하고 **박스 추가**를 누릅니다. 새 에픽은 제목 입력 후 **새 에픽 박스 추가**로 준비합니다. 박스 추가·티켓 이동은 앱에 저장되며 이 단계에서 Jira 이슈를 생성하지 않습니다.

모든 업무는 **에픽 → 작업**의 두 단계로 구성하며 이슈 유형은 **작업(Task)**으로 고정됩니다. 하위업무 구성과 지정·일괄 적용 버튼, 이슈 유형 선택은 제공하지 않습니다. 기존 미등록 하위업무는 내용·근거·에픽 배치를 보존해 독립 작업으로 전환하며, 전환 전 정보는 서버 내부에 보관합니다. 이미 등록되었거나 결과가 미확정인 Jira 이슈는 변경하지 않습니다.

**미배치 업무**의 카드 상단 이동 핸들을 잡아 원하는 에픽 박스로 옮기거나 **에픽 이동** 목록을 사용하세요. 배치는 새로고침 후에도 유지됩니다. 이동한 업무는 승인이 해제되므로 다시 승인한 뒤 등록합니다. 미배치 신규 업무는 먼저 에픽에 배치해야 화면에서 등록할 수 있습니다. 업무 카드에서 **우클릭 → 업무 삭제**로 미등록 업무를 삭제할 수 있으며, 키보드 Shift+F10 또는 카드의 **⋯** 메뉴도 지원합니다. 등록 완료·등록 중·결과 미확정 상태에서는 삭제할 수 없습니다.

기존 Jira 에픽은 그대로 재사용하고, 새 에픽은 배치한 업무를 승인·등록할 때 생성합니다. 새 에픽 설명과 첨부에는 회의록·원본을 반영하며, 조회해 추가한 기존 에픽의 설명·첨부는 변경하지 않습니다. 하나의 회의에서 여러 에픽으로 업무를 나눌 수 있습니다. 등록 완료·등록 중·결과 미확정 상태의 업무는 이동할 수 없습니다.

## 회의록 양식 지정

새 회의 등록 화면 또는 기존 회의의 **회의록 → 회의록 양식 지정**을 누르면 양식 팝업이 열립니다. 제공 양식은 **일반 회의록·주간 업무 보고·프로젝트 진행 회의·의사결정 회의·간단 요약**의 5개입니다. 목록에서 양식을 클릭해 문서 미리보기와 작성 예시를 확인하고 **선택한 양식 적용**을 누르세요. 미리보기만 보거나 취소하면 회의에 적용되지 않습니다.

팝업 안의 **양식 업로드**로 UTF-8 TXT·MD 파일(100KB·20,000자 이하)을 추가하면 목록에 저장됩니다. 업로드한 양식도 미리보고 선택·적용할 수 있으며, 서버를 다시 시작해도 목록이 유지됩니다. 같은 내용의 양식을 다시 올리면 기존 항목을 선택합니다. 선택 양식을 내려받아 수정할 수 있으며 `{{요약}}`, `{{결정사항}}`, `{{논의사항}}` 등 회의 내용 항목을 하나 이상 포함해야 합니다.

기존 회의록이 있으면 내용을 선택한 양식으로 다시 배치합니다. 회의록이 없으면 해당 양식으로 AI 작성을 시작하며, 이후 재생성에도 적용됩니다. 업로드 문서는 텍스트로 미리보기하며 DOCX·HWP·PDF는 TXT·MD로 변환한 뒤 항목 표시를 넣어 등록하세요. HTML 로컬 버전에서는 업로드 목록이 해당 브라우저에 저장되며 JSON 백업에도 포함됩니다.

## 종료

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File .\scripts\stop-remote.ps1
```

스크립트가 기록한 PID·실행 파일·시작 시각이 일치하는 프로세스만 종료합니다. 데이터와 접속 토큰은 유지됩니다.

## 소스 수정 후 빌드

Node.js 22.12 이상 필요:

```powershell
cd frontend
npm ci --include=dev
npm run build:pages
```

출력은 `frontend/pages/index.html`입니다. 로컬 원본 프로젝트에서 GitHub 폴더를 다시 만들 때는 루트의 `scripts/package-github.ps1`을 실행하세요. 기존 `npm run build`는 동일 출처 로컬 개발용이며, `npm run build:html`은 서버 기능이 없는 브라우저 저장용입니다.

## 비공개 파일 및 문제 확인

`.local-remote`, `.tools`, `backend/data`, `node_modules`, `.env`, 로그, 키 파일은 업로드하지 않습니다. 준비된 github 폴더에는 이러한 실제 파일이 포함되지 않습니다. 접속 토큰·AI 키·Jira 키·터널 계정 자격 증명을 GitHub Secrets나 공개 HTML에 넣을 필요가 없습니다.

음성·영상 파일도 공개 소스에 포함하지 않습니다. 소스에 포함된 E2E 테스트 중 미디어 fixture가 필요한 테스트는 별도 테스트용 샘플을 준비해야 합니다. Pages 연결 테스트(`frontend/scripts/test-pages.mjs`)는 합성 데이터만 사용합니다.

연결 오류: 서버 로그 `.local-remote/server.log`, `.local-remote/server-error.log`, 터널 로그 `.local-remote/tunnel.log`를 확인하세요. 401은 접속 토큰, 403/CORS 오류는 허용 출처 설정을 확인합니다. GitHub 저장소 경로는 CORS origin에 포함하지 않습니다. 계정/도메인을 바꾸면 `-PagesOrigin https://계정.github.io`로 시작하세요.

참고: [Cloudflare Tunnel 설치](https://developers.cloudflare.com/tunnel/downloads/) · [Quick Tunnel](https://developers.cloudflare.com/tunnel/get-started/quick-tunnels/) · [GitHub Pages](https://docs.github.com/en/pages/getting-started-with-github-pages/configuring-a-publishing-source-for-your-github-pages-site)
