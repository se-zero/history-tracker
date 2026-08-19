package com.history.backend.integration.domain;

/**
 * 연동 확정 전에 사용자가 골라야 하는 대상 한 단계.
 *
 * <p>provider마다 단계 수가 다르다 — Linear는 team 1단, Jira·Asana는 2단,
 * ClickUp은 workspace → space → folder(선택) → list로 최대 4단이다. 그래서 단계 스키마를 고정하지 않고
 * provider가 선언하게 한다.</p>
 *
 * <p>{@code key}·{@code labelKey}는 그대로 {@code integrations.external_ref}의 키가 된다 —
 * pipeline-worker가 수집할 때 읽는 키와 같아야 하므로 provider가 자기 키 이름을 정한다.</p>
 *
 * @param key      선택 값을 저장할 external_ref 키 (예: {@code cloud_id})
 * @param labelKey 표시 이름을 저장할 external_ref 키. 저장이 필요 없으면 null (예: {@code site_name})
 * @param title    화면에 보여줄 단계 이름 (예: "사이트")
 * @param optional 건너뛸 수 있는 단계인지 — ClickUp의 folder처럼 없어도 하위 단계로 갈 수 있는 경우 true
 */
public record SelectionStep(
        String key,
        String labelKey,
        String title,
        boolean optional
) {

    public static SelectionStep required(String key, String labelKey, String title) {
        return new SelectionStep(key, labelKey, title, false);
    }

    public static SelectionStep optional(String key, String labelKey, String title) {
        return new SelectionStep(key, labelKey, title, true);
    }
}
