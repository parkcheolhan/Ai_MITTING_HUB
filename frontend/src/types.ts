export type Segment = {
  id: string;
  speaker: string;
  startMs: number;
  endMs: number;
  text: string;
  estimatedTime: boolean;
};
export type Evidence = {
  segmentId: string;
  startMs: number;
  speaker: string;
  quote: string;
  estimatedTime: boolean;
};
export type Item = {
  id: string;
  kind: "ACTION" | "CHECK";
  title: string;
  tasks: string[];
  checks: string[];
  acceptanceCriteria: string[];
  dependencies: string[];
  evidence: Evidence[];
  confidence: number;
  inferred: boolean;
  assigneeSuggestion: string | null;
  dueDateSuggestion: string | null;
};
export type Minutes = {
  formatted?: string;
  generationMode?: string;
  generationModel?: string;
  version: number;
  overview: string;
  summary: string;
  discussion: string;
  decisions: string;
  openQuestions: string;
  followUp: string;
};
export type Draft = {
  generationMode?: string;
  generationModel?: string;
  analysisVersion?: number;
  id: string;
  meetingId: string;
  projectKey: string;
  issueType: string;
  summary: string;
  description: string;
  assignee: string;
  assigneeName?:string;
  startAt?:string;
  endAt?:string;
  estimatedMinutes?:number;
  scheduleMode?:"MANUAL"|"AI";
  scheduleReason?:string;
  dependencyDraftIds?:string[];
  priority: string;
  dueDate: string;
  labels: string[];
  parent: string;
  parentDraftId?: string;
  registrationMode?: "NEW" | "EXISTING";
  existingIssueKey?: string;
  epicKey?: string;
  analysisItemIds: string[];
  status: string;
  issueKey: string | null;
  issueUrl: string | null;
  publishedAt: string | null;
  error: string | null;
  duplicateSuspected: boolean;
  version: number;
};
export type Job = {
  stage?: string;
  id: string;
  meetingId: string;
  type: string;
  status: string;
  progress: number;
  errorMessage: string | null;
};
export type Meeting = {
  epics?: {id:string;projectKey:string;summary:string;status:string;issueKey:string|null;issueUrl:string|null;error:string|null;contentStatus?:string;contentError?:string|null}[];
  id: string;
  title: string;
  status: string;
  createdAt: string;
  drafts: Draft[];
  jobs: Job[];
  content: {
    occurredAt: string;
    projectKey: string;
    participants: string[];
    memo: string;
    source: string;
    language: string;
    template: string;
    diarization: boolean;
    segments: Segment[];
    speakerGroups?: Record<string,string>;
    minutes: Minutes | null;
    items: Item[];
    analysisMode?: string;
    analysisModel?: string;
    analysisVersion?: number;
    assets: {
      id: string;
      originalName: string;
      mediaType: string;
      size: number;
    }[];
    scheduleSettings?:{startAt:string;timezone:string;workStartHour:number;workEndHour:number;availability:{assignee:string;availableAt:string;workStartHour:number;workEndHour:number}[]};
  };
};
export const statusName: Record<string, string> = {
  UPLOADED: "자료 등록",
  QUEUED: "분석 대기",
  TRANSCRIBING: "음성 변환 중",
  IDENTIFYING_SPEAKERS: "음성별 화자 분석 중",
  GENERATING_MINUTES: "회의록 생성 중",
  ANALYZING: "업무 분석 중",
  GENERATING_TICKETS: "AI 티켓 정리 중",
  ESTIMATING_SCHEDULE:"AI 작업시간 분석 중",
  ALLOCATING_SCHEDULE:"담당자별 일정 배정 중",
  REVIEW_PENDING: "검토 대기",
  COMPLETED: "완료",
  FAILED: "실패",
  CANCELLED: "취소됨",
  DRAFT: "초안",
  APPROVED: "승인됨",
  PUBLISHED: "등록 완료",
  PUBLISHING: "등록 중",
  UNKNOWN: "생성 결과 확인 필요",
  RUNNING: "처리 중",
  BLOCKED: "승인 필요",
  SKIPPED: "기등록 · 건너뜀",
};
export const time = (ms: number) => new Date(ms).toISOString().slice(11, 19);
export const activeJob = (m: Meeting) =>
  m.jobs.find((j) => ["QUEUED", "RUNNING"].includes(j.status));
