import { useEffect, useState } from "react";
import { api } from "./api";
export type JiraSettings = {
  mode: "mock" | "cloud";
  siteUrl: string;
  defaultProject: string;
  configured: boolean;
  missing: string[];
  tokenConfigured: boolean;
  email?: string;
  cloudId?: string;
  configurationSource?: "database" | "environment";
  ready?: boolean;
};
export type Project = { key: string; name: string };
export type IssueType = { id: string; name: string; subtask?: string };
export type JiraField = {
  id: string;
  name: string;
  required: boolean;
  hasDefaultValue: boolean;
  allowedValues: { id: string; name: string }[];
};
export function useJiraSettings() {
  const [settings, setSettings] = useState<JiraSettings>({
    mode: "mock",
    siteUrl: "",
    defaultProject: "",
    configured: false,
    missing: [],
    tokenConfigured: false,
    ready: false,
  });
  useEffect(() => {
    let active = true;
    const reload = () =>
      api<JiraSettings>("/settings")
        .then((value) => {
          if (active) setSettings({ ...value, ready: true });
        })
        .catch(() => {});
    void reload();
    window.addEventListener("jira-settings-changed", reload);
    return () => {
      active = false;
      window.removeEventListener("jira-settings-changed", reload);
    };
  }, []);
  return settings;
}
export function notifyJiraSettingsChanged() {
  window.dispatchEvent(new Event("jira-settings-changed"));
}
