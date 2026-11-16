export default function JiraConnectionGuide({siteUrl,tokenConfigured}:{siteUrl:string;tokenConfigured:boolean}){
  let tenantUrl='';
  try{const site=new URL(siteUrl);if(site.protocol==='https:'&&site.hostname.endsWith('.atlassian.net')&&!site.username&&!site.password&&!site.port)tenantUrl=site.origin+'/_edge/tenant_info';}catch{/* Show the link after a valid Jira site is entered. */}
  return <div className="jira-connection-guide">
    <details open={!tokenConfigured}>
      <summary>Jira API 토큰 발급 가이드</summary>
      <ol>
        <li><strong>Atlassian 계정으로 로그인</strong><p>Jira에서 사용하는 계정으로 로그인하세요. 회사 SSO를 사용하는 계정은 회사 로그인 절차를 진행합니다.</p><a href="https://id.atlassian.com/manage-profile/security/api-tokens" target="_blank" rel="noopener noreferrer">API 토큰 발급 페이지 열기 ↗</a></li>
        <li><strong>토큰 생성 후 복사</strong><p>용도를 알아볼 수 있는 이름과 만료일을 지정하세요. 스코프 토큰은 Jira를 선택하고 사용자·프로젝트·이슈 조회, 생성·수정·상태 전환·첨부에 필요한 권한을 지정합니다. 발급된 값은 생성 직후 복사하세요.</p></li>
        <li><strong>아래 입력란에 등록하고 연결 테스트</strong><p>사이트 URL, 계정 이메일, API 토큰을 입력하고 연결 설정을 저장하세요. 스코프 토큰은 Cloud ID도 필요합니다. 연결 테스트 후 기본 프로젝트를 선택합니다.</p>{tenantUrl?<a href={tenantUrl} target="_blank" rel="noopener noreferrer">이 Jira 사이트의 Cloud ID 확인 ↗</a>:<p>Jira 사이트 URL을 입력하면 Cloud ID 확인 링크가 표시됩니다.</p>}</li>
      </ol>
      <p className="help">토큰은 다시 조회할 수 없으며 만료되거나 삭제되면 새로 발급해야 합니다. 발급이 제한되면 조직 관리자에게 계정의 API 토큰 정책을 확인하세요.</p>
      <a href="https://support.atlassian.com/atlassian-account/docs/manage-api-tokens-for-your-atlassian-account/" target="_blank" rel="noopener noreferrer">Atlassian 공식 토큰 발급 안내 ↗</a>
    </details>
    <details>
      <summary>회사 SSO로 토큰 입력 없이 연결하려면?</summary>
      <p>SSO는 Atlassian에 로그인하는 방식입니다. SSO 로그인만으로 이 앱에 API 토큰이 전달되지는 않습니다.</p>
      <p>토큰을 직접 입력하지 않는 연결은 OAuth 2.0(3LO) 방식으로 구현할 수 있습니다. Atlassian 로그인 → 접근 권한 동의 → Jira 사이트 선택 → 연결 완료 순서입니다.</p>
      <p>현재 앱은 API 토큰 연결을 지원합니다. OAuth 연결에는 개발자 콘솔의 앱 등록, Client ID·Secret, 콜백 URL 설정과 앱의 인증·갱신 처리가 추가로 필요합니다.</p>
      <a href="https://developer.atlassian.com/cloud/oauth/getting-started/implementing-oauth-3lo/" target="_blank" rel="noopener noreferrer">Atlassian 공식 OAuth 연동 안내 ↗</a>
    </details>
  </div>;
}
