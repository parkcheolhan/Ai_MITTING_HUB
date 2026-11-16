import { useEffect, useState } from "react";
import { api } from "./api";
export type AiSettings = {
  mode: "openai" | "codex" | "mock";
  model: string;
  keyConfigured: boolean;
  configured: boolean;
  ready?: boolean;
  configurationSource?: string;
};
export function useAiSettings() {
  const [settings, setSettings] = useState<AiSettings>({
    mode: "openai",
    model: "gpt-4.1-mini",
    keyConfigured: false,
    configured: false,
    ready: false,
  });
  useEffect(() => {
    let active = true;
    const reload = () =>
      api<AiSettings>("/settings/ai")
        .then((value) => {
          if (active) setSettings({ ...value, ready: true });
        })
        .catch(() => {});
    void reload();
    window.addEventListener("ai-settings-changed", reload);
    return () => {
      active = false;
      window.removeEventListener("ai-settings-changed", reload);
    };
  }, []);
  return settings;
}
export function notifyAiSettingsChanged() {
  window.dispatchEvent(new Event("ai-settings-changed"));
}
