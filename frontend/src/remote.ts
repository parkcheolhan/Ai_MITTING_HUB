export const pagesMode = (globalThis as {MOA_PAGES?:boolean}).MOA_PAGES === true;
const key='moa-server-connection-v1';
export type Connection={server:string;token:string};
export function connection():Connection {
  if(!pagesMode)return {server:'',token:''};
  try{return JSON.parse(sessionStorage.getItem(key)||'null') || {server:'',token:''};}catch{return {server:'',token:''};}
}
export function validateServer(value:string){
  const url=new URL(value.trim());
  if(url.protocol!=='https:'||url.username||url.password||url.search||url.hash||url.pathname!=='/')throw new Error('API 서버는 경로 없는 HTTPS 주소로 입력하세요. 예: https://api.example.com');
  return url.origin;
}
export function rememberConnection(value:Connection){sessionStorage.setItem(key,JSON.stringify(value));}
export function disconnect(){sessionStorage.removeItem(key);location.reload();}
export function apiUrl(path:string){return connection().server+'/api'+path;}
export function authHeaders():Record<string,string>{const {token}=connection();return pagesMode&&token?{Authorization:'Bearer '+token}:{};}
