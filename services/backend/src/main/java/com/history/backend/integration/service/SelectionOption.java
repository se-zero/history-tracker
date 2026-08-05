package com.history.backend.integration.service;

// 한 선택 단계의 후보 하나. value는 external_ref에 저장되고 label은 화면 표시용이다.
public record SelectionOption(String value, String label) {
}
