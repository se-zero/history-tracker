"""label_cli.py 단위 테스트 (오프라인 — 파일 입출력은 tmp_path, 키 입력은 스크립트로 주입).

라벨링 도구가 틀리면 조용히 precision 비교가 오염된다. 라벨이 엉뚱한 항목에 들어가거나, 저장하다
다른 줄이 바뀌거나, 화면에 모델·점수 단서가 새면 안 되므로 그 셋을 중심으로 시험한다.

실행: services/ai-engine/.venv/Scripts/python.exe -m pytest eval/tests
"""

import io
import os
import subprocess
import sys
import unicodedata

import pytest
import yaml

EVAL_DIR = os.path.join(os.path.dirname(os.path.abspath(__file__)), "..")
sys.path.insert(0, EVAL_DIR)

import label_cli  # noqa: E402

CLEAR = "\x1b[2J\x1b[H"


# ─── 픽스처: 실제 큐 파일과 같은 모양(주석 헤더 · 리터럴 블록 · 열 0의 `- ` 항목)의 텍스트 ───────────────────

class _Dumper(yaml.SafeDumper):
    pass


def _str_representer(dumper, data):
    """sample_edges와 같은 설정 — 여러 줄 문자열은 리터럴 블록(|-)으로."""
    return dumper.represent_scalar("tag:yaml.org,2002:str", data, style="|" if "\n" in data else None)


_Dumper.add_representer(str, _str_representer)

HEADER = "# 라벨 큐 — 시험용 헤더\n# label 칸만 채우세요: relevant | irrelevant | unsure\n"


def _queue_text(entries):
    body = yaml.dump({"seed": 42, "edges": entries}, Dumper=_Dumper, allow_unicode=True,
                     sort_keys=False, default_flow_style=False, width=100)
    return HEADER + body


def _commit(hash7, message="fix: 버그 수정\n\n원인과 해결 설명", files=("a.py", "b.py")):
    return {"type": "commit", "id": hash7,
            "body": {"hash": hash7 + "0123456789abcdef0123456789abcdef012", "message": message, "files": list(files)}}


def _issue(key, title="로그인 오류", body="", status="완료"):
    return {"type": "issue", "id": key, "body": {"key": key, "title": title, "body": body, "status": status}}


def _thread(conv_id, messages=None):
    messages = messages or [("안녕하세요", "2026-06-26T05:27:07.362269Z", "일반", "정세영"),
                            ("확인했습니다", "2026-06-26T05:39:38.031059Z", "일반", "서준수")]
    return {"type": "message", "id": conv_id,
            "body": {"conversation_id": conv_id,
                     "messages": [{"body": b, "occurred_at": t, "channel": c, "author": a} for b, t, c, a in messages]}}


def _entry(edge_type, src, dst, label=None, note=None, **extra):
    return {"edge_type": edge_type, "src": src, "dst": dst, "label": label, "note": note, **extra}


def _sample_entries():
    return [
        _entry("REFERENCE", _commit("1111111"), _thread("1.1")),                                  # 0 미라벨
        _entry("DISCUSSED_IN", _issue("HT-2", "DB 설계"), _thread("2.2")),                        # 1 미라벨
        _entry("TRIGGERED_BY", _commit("3333333"), _issue("HT-3", "배포 실패", "원문 설명")),     # 2 미라벨
        _entry("REFERENCE", _commit("4444444"), _thread("4.4"), label="relevant", note="이미 라벨"),  # 3 라벨됨
        _entry("REFERENCE", _commit("5555555", message="chore: 정리\n\nlabel: 가짜\n  label: 가짜2"),
               _thread("5.5", [("label: 본문 속 문자열\n  label: 들여쓴 문자열", "2026-06-27T01:02:03Z", "개발", "김")])),  # 4 미라벨
    ]


SAMPLE_TEXT = _queue_text(_sample_entries())


def _write(tmp_path, text=SAMPLE_TEXT, name="queue.yaml"):
    path = tmp_path / name
    path.write_text(text, encoding="utf-8", newline="")
    return str(path)


def _read(path):
    with open(path, encoding="utf-8", newline="") as f:
        return f.read()


def _labels(path):
    return [e["label"] for e in yaml.safe_load(_read(path))["edges"]]


def _cells(text):
    """터미널 칸 수 — 전각(W·F)은 2칸, 결합 문자는 0칸. label_cli.display_width와 일부러 따로 적었다."""
    return sum(0 if unicodedata.combining(c) else 2 if unicodedata.east_asian_width(c) in ("W", "F") else 1
               for c in text)


def _rows(lines, width):
    """터미널이 이 줄들을 그리는 데 쓰는 행 수 — 폭을 넘는 줄은 다음 행으로 접힌다."""
    return sum(max(1, -(-_cells(line) // width)) for line in lines)


def _item_starts(text):
    return [i for i, line in enumerate(text.split("\n")) if line.startswith("- ")]


# ─── 키 입력을 스크립트로 주입해 대화 루프를 돌리는 도우미 ──────────────────────────────────────────────

class Script:
    """read_key 대체. 키를 하나씩 내주고, 호출 시점의 파일 내용·누적 출력 길이를 기록한다.

    키가 모자라면 루프가 더 기다린다는 뜻이므로 멈추지 않고 실패시킨다. 예외 클래스·인스턴스를 넣으면 그 예외를 낸다.
    """

    def __init__(self, keys, path, out):
        self.keys, self.path, self.out = list(keys), path, out
        self.files_at_call, self.out_len_at_call = [], []
        self.before_call = None

    def __call__(self):
        if self.before_call:
            self.before_call()
        self.files_at_call.append(_read(self.path))
        self.out_len_at_call.append(len("".join(self.out)))
        if not self.keys:
            raise AssertionError("스크립트된 키가 모자란다 — 대화 루프가 키를 더 기다린다")
        key = self.keys.pop(0)
        if isinstance(key, BaseException) or (isinstance(key, type) and issubclass(key, BaseException)):
            raise key
        return key


def _run(path, keys, review=False, width=80, height=40):
    out = []
    script = Script(keys, path, out)
    code = label_cli.run_session(path, review=review, read_key=script, write=out.append,
                                 get_width=lambda: width, get_height=lambda: height)
    return code, "".join(out), script


# ─── set_label_in_text — 한 줄만 바꾼다 ─────────────────────────────────────────────────────────

def test_fixture_has_the_layout_the_tool_edits():
    """이 픽스처가 실제 큐 파일과 같은 모양이라는 전제를 고정한다 (아니면 아래 시험이 의미가 없다)."""
    lines = SAMPLE_TEXT.split("\n")

    assert lines[0].startswith("# ")
    assert len(_item_starts(SAMPLE_TEXT)) == 5
    assert "  label: null" in lines and "  label: relevant" in lines
    assert any(line.strip() == "message: |-" for line in lines)


@pytest.mark.parametrize("index", range(5))
@pytest.mark.parametrize("label", ["relevant", "irrelevant", "unsure"])
def test_set_label_changes_only_that_items_label_line(index, label):
    new = label_cli.set_label_in_text(SAMPLE_TEXT, index, label)

    old_lines, new_lines = SAMPLE_TEXT.split("\n"), new.split("\n")
    assert len(old_lines) == len(new_lines)
    changed = [i for i, (a, b) in enumerate(zip(old_lines, new_lines)) if a != b]

    starts = _item_starts(SAMPLE_TEXT) + [len(old_lines)]
    already = yaml.safe_load(SAMPLE_TEXT)["edges"][index]["label"]
    if already == label:                                    # 같은 값으로 다시 줘도 다른 줄은 안 건드린다
        assert changed == []
    else:
        assert len(changed) == 1
        assert starts[index] < changed[0] < starts[index + 1]            # 그 항목 안의 줄이다
        assert new_lines[changed[0]] == f"  label: {label}"
        assert old_lines[changed[0]].startswith("  label:")

    expected = yaml.safe_load(SAMPLE_TEXT)
    expected["edges"][index]["label"] = label
    assert yaml.safe_load(new) == expected                                # 파싱 결과도 label만 다르다


def test_set_label_ignores_label_like_text_inside_literal_blocks():
    """커밋 메시지·대화 본문 안의 `label:` 문자열이 아니라 항목의 `  label:` 키를 바꾼다."""
    new = label_cli.set_label_in_text(SAMPLE_TEXT, 4, "irrelevant")

    parsed = yaml.safe_load(new)["edges"][4]
    assert parsed["label"] == "irrelevant"
    assert parsed["src"]["body"]["message"] == "chore: 정리\n\nlabel: 가짜\n  label: 가짜2"
    assert parsed["dst"]["body"]["messages"][0]["body"] == "label: 본문 속 문자열\n  label: 들여쓴 문자열"


def test_set_label_keeps_crlf_line_endings():
    crlf = SAMPLE_TEXT.replace("\n", "\r\n")

    new = label_cli.set_label_in_text(crlf, 0, "unsure")

    assert new.count("\r\n") == crlf.count("\r\n")
    assert new == crlf.replace("  label: null\r\n", "  label: unsure\r\n", 1)


def test_set_label_without_label_line_fails_loudly_instead_of_redumping():
    text = SAMPLE_TEXT.replace("  label: null\n", "", 1)                # 0번 항목의 label 줄을 지운다

    with pytest.raises(label_cli.QueueFileError, match="label"):
        label_cli.set_label_in_text(text, 0, "relevant")


def test_set_label_rejects_values_outside_the_allowed_labels():
    with pytest.raises(ValueError):
        label_cli.set_label_in_text(SAMPLE_TEXT, 0, "maybe")


def test_set_label_with_item_index_out_of_range_fails():
    with pytest.raises(label_cli.QueueFileError):
        label_cli.set_label_in_text(SAMPLE_TEXT, 9, "relevant")


# ─── 대상 선택 · 요약 ───────────────────────────────────────────────────────────────────────────

def _items_with_labels(*labels):
    return [_entry("REFERENCE", _commit(f"{i:07d}"), _thread(f"{i}.0"), label=label) for i, label in enumerate(labels)]


def test_select_indices_default_takes_only_unlabeled_or_invalid_in_file_order():
    items = _items_with_labels(None, "relevant", "", "maybe", "unsure", "Irrelevant ", None)

    assert label_cli.select_indices(items, review=False) == [0, 2, 3, 6]


def test_select_indices_review_takes_every_item():
    items = _items_with_labels(None, "relevant", "maybe")

    assert label_cli.select_indices(items, review=True) == [0, 1, 2]


def test_count_labels_counts_valid_labels_and_leaves_invalid_ones_as_remaining():
    items = _items_with_labels("relevant", "relevant", "irrelevant", "unsure", None, "maybe")

    assert label_cli.count_labels(items) == {"relevant": 2, "irrelevant": 1, "unsure": 1}


def test_summary_points_to_fill_command_only_when_nothing_remains():
    done = "\n".join(label_cli.summary_lines(_items_with_labels("relevant", "unsure"), "eval/q 1.yaml"))
    left = "\n".join(label_cli.summary_lines(_items_with_labels("relevant", None), "eval/q.yaml"))

    assert "relevant 1" in done and "unsure 1" in done and "남은 0개" in done
    assert "label_queue.py fill --queue 'eval/q 1.yaml' <표본 파일들>" in done
    assert "services/ai-engine/.venv/bin/python eval/label_queue.py fill" in done
    assert "남은 1개" in left and "label_queue.py fill" not in left


# ─── render_item — 세 가지 항목 모양, 축약과 전체 ────────────────────────────────────────────────

def test_render_reference_shows_commit_and_thread_with_timestamps():
    lines = label_cli.render_item(_sample_entries()[0], width=80, full=False)
    text = "\n".join(lines)

    assert "커밋 1111111" in text
    assert "fix: 버그 수정" in text and "원인과 해결 설명" in text
    assert "a.py" in text and "b.py" in text
    assert "대화(스레드) 2개 메시지" in text
    assert "[2026-06-26 05:27 UTC] 정세영 (일반): 안녕하세요" in text
    assert "[2026-06-26 05:39 UTC] 서준수 (일반): 확인했습니다" in text
    assert text.index("커밋 1111111") < text.index("대화(스레드)")          # src가 위, dst가 아래


def test_render_discussed_in_shows_issue_with_status_and_empty_body():
    text = "\n".join(label_cli.render_item(_sample_entries()[1], width=80, full=False))

    assert "이슈 HT-2 [완료]" in text
    assert "DB 설계" in text
    assert "대화(스레드) 2개 메시지" in text


def test_render_triggered_by_shows_commit_then_issue_with_body():
    text = "\n".join(label_cli.render_item(_sample_entries()[2], width=80, full=False))

    assert "커밋 3333333" in text and "이슈 HT-3 [완료]" in text
    assert "배포 실패" in text and "원문 설명" in text
    assert text.index("커밋 3333333") < text.index("이슈 HT-3")


def test_render_commit_lists_at_most_12_files_then_counts_the_rest():
    files = [f"src/file_{i:02d}.py" for i in range(15)]
    item = _entry("REFERENCE", _commit("1111111", files=files), _thread("1.1"))

    short = "\n".join(label_cli.render_item(item, width=80, full=False))
    full = "\n".join(label_cli.render_item(item, width=80, full=True))

    assert "src/file_11.py" in short and "src/file_12.py" not in short
    assert "… 외 3개" in short
    assert "src/file_14.py" in full and "외 3개" not in full


def _long_text(prefix, n=400):
    return " ".join(f"{prefix}{i:04d}" for i in range(n))                  # 길이 n*(len+1)-1


@pytest.mark.parametrize("make_item, head, tail", [
    (lambda t: _entry("REFERENCE", _commit("1111111", message=t, files=[]), _thread("1.1")), "w0000", "w0399"),
    (lambda t: _entry("DISCUSSED_IN", _issue("HT-9", "제목", body=t), _thread("1.1")), "w0000", "w0399"),
    (lambda t: _entry("REFERENCE", _commit("1111111"),
                      _thread("1.1", [(t, "2026-06-26T05:27:07Z", "일반", "정세영")])), "w0000", "w0399"),
])
def test_render_truncates_long_blocks_by_rows_and_full_shows_everything(make_item, head, tail):
    text = _long_text("w")
    item = make_item(text)

    short = "\n".join(label_cli.render_item(item, width=80, full=False, rows=14))
    full = "\n".join(label_cli.render_item(item, width=80, full=True, rows=14))       # 전체 보기는 행 제한을 무시한다

    assert head in short and tail not in short
    assert "줄 생략, f 키로 전체 보기)" in short
    assert tail in full and "생략" not in full


def test_render_truncation_uses_the_row_budget_and_reports_exactly_how_many_lines_are_hidden():
    item = _entry("REFERENCE", _commit("1111111", message=_long_text("w"), files=[]), _thread("1.1"))

    full = label_cli.render_item(item, width=80, full=True)
    short = label_cli.render_item(item, width=80, full=False, rows=20)

    assert len(short) == 20                                                  # 주어진 행을 다 쓴다
    assert f"… ({len(full) - len(short) + 1}줄 생략, f 키로 전체 보기)" in "\n".join(short)    # 마커가 한 줄을 차지한다
    assert short[-1] == full[-1]                                             # 짧은 dst 블록은 잘리지 않는다


def test_render_does_not_truncate_blocks_that_fit_the_row_budget():
    text = "\n".join(label_cli.render_item(_sample_entries()[0], width=80, full=False, rows=30))

    assert "생략" not in text


def test_render_without_row_budget_does_not_truncate():
    item = _entry("REFERENCE", _commit("1111111", message=_long_text("w"), files=[]), _thread("1.1"))

    assert "w0399" in "\n".join(label_cli.render_item(item, width=80, full=False))


@pytest.mark.parametrize("width", [40, 80, 120])
def test_render_wraps_to_terminal_width(width):
    item = _entry("REFERENCE", _commit("1111111", message=_long_text("w", 60)),
                  _thread("1.1", [(_long_text("v", 60), "2026-06-26T05:27:07Z", "일반", "정세영")]))

    lines = label_cli.render_item(item, width=width, full=True)

    assert max(_cells(line) for line in lines) <= width
    assert "w0059" in "\n".join(lines) and "v0059" in "\n".join(lines)        # 줄바꿈으로 내용이 사라지지 않는다


def test_render_wraps_long_unbroken_tokens_instead_of_overflowing():
    item = _entry("REFERENCE", _commit("1111111", message="x" * 300, files=["p/" + "y" * 200]), _thread("1.1"))

    lines = label_cli.render_item(item, width=60, full=True)

    assert max(_cells(line) for line in lines) <= 60


def test_render_shows_no_score_or_model_hints_even_if_the_entry_carries_them():
    item = _entry("REFERENCE", _commit("1111111"), _thread("1.1"),
                  confidence=0.4321, bucket="0.40-0.50", score=0.87, model="text-embedding-3-large")

    text = "\n".join(label_cli.render_item(item, width=80, full=True)).lower()

    for hint in ("confidence", "score", "bucket", "model", "0.4321", "0.87", "0.40-0.50", "embedding"):
        assert hint not in text


# ─── 대화 루프 — 스크립트된 키 ──────────────────────────────────────────────────────────────────

def test_riu_keys_save_each_label_to_the_shown_item_right_away(tmp_path):
    path = _write(tmp_path)

    code, out, script = _run(path, ["r", "i", "u", "q"])

    assert code == 0
    assert _labels(path) == ["relevant", "irrelevant", "unsure", "relevant", None]       # 대상은 0·1·2·4번
    # 다음 키를 기다리는 시점에는 앞선 라벨이 이미 파일에 있다 (종료 때 몰아 쓰지 않는다)
    assert _labels_of_text(script.files_at_call[1])[0] == "relevant"
    assert _labels_of_text(script.files_at_call[2])[:2] == ["relevant", "irrelevant"]
    assert script.keys == []


def _labels_of_text(text):
    return [e["label"] for e in yaml.safe_load(text)["edges"]]


def test_labeling_keeps_everything_else_byte_for_byte(tmp_path):
    path = _write(tmp_path)

    _run(path, ["r", "i", "u", "q"])

    expected = SAMPLE_TEXT
    for index, label in [(0, "relevant"), (1, "irrelevant"), (2, "unsure")]:
        expected = label_cli.set_label_in_text(expected, index, label)
    assert _read(path) == expected
    assert _read(path).startswith(HEADER)


def test_uppercase_keys_are_accepted(tmp_path):
    path = _write(tmp_path)

    _run(path, ["R", "I", "U", "Q"])

    assert _labels(path)[:3] == ["relevant", "irrelevant", "unsure"]


def test_skip_leaves_label_null_and_moves_to_next_item(tmp_path):
    path = _write(tmp_path)

    code, out, script = _run(path, ["s", "r", "q"])

    assert code == 0
    assert _labels(path) == [None, "relevant", None, "relevant", None]        # 0번은 건너뜀, 다음 키는 1번에
    assert _read(path) == label_cli.set_label_in_text(SAMPLE_TEXT, 1, "relevant")


def test_skip_everything_changes_nothing_and_says_items_remain(tmp_path):
    path = _write(tmp_path)

    code, out, script = _run(path, ["s", "s", "s", "s", "q"])

    assert code == 0
    assert _read(path) == SAMPLE_TEXT
    assert not os.path.exists(path + ".orig")
    assert "끝까지 봤지만 건너뛴 항목이 있습니다" in out and "남은 4개" in out    # 남은 게 있으면 '모두 끝났습니다'가 아니다
    assert "모두 끝났습니다" not in out
    assert "label_queue.py fill" not in out


def test_back_returns_to_previous_item_and_relabels_it(tmp_path):
    path = _write(tmp_path)

    code, out, script = _run(path, ["r", "b", "i", "u", "q"])

    # r(0번) → b(0번으로) → i(0번을 irrelevant로 고침) → u(1번) → q
    assert _labels(path) == ["irrelevant", "unsure", None, "relevant", None]
    assert out.count("현재 라벨: relevant") == 1                                # 돌아가서 본 화면에만
    assert script.keys == []


def test_back_goes_one_step_at_a_time_not_to_the_start(tmp_path):
    path = _write(tmp_path)

    # r(0번) i(1번) → b(1번으로) u(1번을 unsure로) → 이제 2번 → b b(2번 → 1번 → 0번) i(0번을 irrelevant로)
    code, out, script = _run(path, ["r", "i", "b", "u", "b", "b", "i", "q"])

    assert _labels(path) == ["irrelevant", "unsure", None, "relevant", None]
    assert script.keys == []


def test_back_also_returns_to_a_skipped_item(tmp_path):
    path = _write(tmp_path)

    _run(path, ["s", "b", "u", "q"])

    assert _labels(path)[:2] == ["unsure", None]


def test_back_on_first_item_is_ignored_with_a_notice(tmp_path):
    path = _write(tmp_path)

    code, out, script = _run(path, ["b", "r", "q"])

    assert "첫 항목" in out
    assert _labels(path)[:2] == ["relevant", None]


def test_q_quits_at_once_and_shows_summary(tmp_path):
    path = _write(tmp_path)

    code, out, script = _run(path, ["q", "r"])

    assert code == 0
    assert _read(path) == SAMPLE_TEXT
    assert script.keys == ["r"]                                              # q 뒤의 키는 읽지 않는다
    assert "relevant 1" in out and "남은 4개" in out


def test_unknown_keys_are_ignored_without_touching_the_screen_or_file(tmp_path):
    path = _write(tmp_path)

    code, out, script = _run(path, ["x", "1", " ", "\x1b", "", "\n", "r", "q"])

    assert _labels(path)[:2] == ["relevant", None]
    # 모르는 키(0~5번째 호출) 사이에는 출력도 파일도 늘지 않는다
    assert len(set(script.out_len_at_call[:7])) == 1
    assert set(script.files_at_call[:7]) == {SAMPLE_TEXT}


@pytest.mark.parametrize("interrupt", [KeyboardInterrupt, "\x03", None])
def test_ctrl_c_and_eof_end_cleanly_keep_saved_labels_and_show_summary(tmp_path, interrupt):
    path = _write(tmp_path)

    code, out, script = _run(path, ["r", interrupt])

    assert code == 0
    assert _labels(path)[:2] == ["relevant", None]
    assert "relevant 2" in out                                               # 3번 항목의 기존 라벨 포함 파일 전체 기준
    assert "남은 3개" in out


def test_all_done_shows_finish_message_summary_and_fill_command(tmp_path):
    path = _write(tmp_path)

    code, out, script = _run(path, ["r", "i", "u", "r", "q"])

    assert code == 0
    assert "모두 끝났습니다" in out
    assert "relevant 3 · irrelevant 1 · unsure 1" in out and "남은 0개" in out
    assert f"label_queue.py fill --queue {path} <표본 파일들>" in out
    assert script.keys == []


def test_nothing_to_label_finishes_without_reading_keys(tmp_path):
    path = _write(tmp_path, _queue_text([_entry("REFERENCE", _commit("1111111"), _thread("1.1"), label="relevant")]))

    code, out, script = _run(path, [])

    assert code == 0 and "모두 끝났습니다" in out and "남은 0개" in out


def test_screen_header_shows_progress_remaining_type_and_question(tmp_path):
    path = _write(tmp_path)

    code, out, script = _run(path, ["r", "s", "s", "q"])
    screens = out.split(CLEAR)[1:]

    assert "[진행 1/4] 남은 4개 · 유형 REFERENCE" in screens[0]
    assert "이 커밋이 아래 대화(스레드)와 실제로 같은 사안을 다루나?" in screens[0]
    assert "[진행 2/4] 남은 3개 · 유형 DISCUSSED_IN" in screens[1]               # 라벨이 하나 생겼다
    assert "이 이슈가 아래 대화에서 실제로 논의됐나?" in screens[1]
    assert "[진행 3/4] 남은 3개 · 유형 TRIGGERED_BY" in screens[2]               # 건너뛰면 남은 수는 그대로
    assert "이 커밋이 이 이슈 때문에 생긴 변경인가?" in screens[2]


def test_each_item_is_drawn_after_clearing_the_screen(tmp_path):
    path = _write(tmp_path)

    code, out, script = _run(path, ["s", "q"])

    assert out.startswith(CLEAR)
    assert "커밋 1111111" in out.split(CLEAR)[1] and "커밋 1111111" not in out.split(CLEAR)[2]
    assert "이슈 HT-2" in out.split(CLEAR)[2]


def test_screens_never_hint_at_scores_or_models(tmp_path):
    entries = [_entry("REFERENCE", _commit("1111111"), _thread("1.1"),
                      confidence=0.4321, bucket="0.40-0.50", score=0.87)]
    path = _write(tmp_path, _queue_text(entries))

    code, out, script = _run(path, ["?", "f", "b", "r", "q"])

    lowered = out.lower()
    for hint in ("confidence", "score", "bucket", "0.4321", "0.87", "0.40-0.50"):
        assert hint not in lowered


def test_f_toggles_between_truncated_and_full_view(tmp_path):
    entries = [_entry("REFERENCE", _commit("1111111", message=_long_text("w"), files=[]), _thread("1.1"))]
    path = _write(tmp_path, _queue_text(entries))

    code, out, script = _run(path, ["f", "f", "q"])
    first, expanded, collapsed = out.split(CLEAR)[1:4]

    assert "w0399" not in first and "줄 생략" in first
    assert "w0399" in expanded and "생략" not in expanded
    assert "w0399" not in collapsed and "줄 생략" in collapsed


def test_help_key_explains_labels_without_changing_anything(tmp_path):
    path = _write(tmp_path)

    code, out, script = _run(path, ["?", "q"])
    first, with_help = out.split(CLEAR)[1:3]

    assert "원문만으로 판단 불가" not in first
    assert "원문만으로 판단 불가" in with_help
    assert _read(path) == SAMPLE_TEXT


def test_start_screen_warns_about_editors_holding_the_file(tmp_path):
    path = _write(tmp_path)

    code, out, script = _run(path, ["s", "q"])
    first, second = out.split(CLEAR)[1:3]

    assert "편집기에서 이 파일을 열어 두었다면 닫고 쓰세요" in first
    assert "편집기에서 이 파일을 열어 두었다면" not in second


def test_default_mode_skips_labeled_items_and_review_mode_includes_them(tmp_path):
    path = _write(tmp_path)

    _, default_out, _ = _run(path, ["s", "s", "s", "s", "q"])
    code, review_out, script = _run(path, ["s", "s", "s", "u", "q"], review=True)

    assert "4444444" not in default_out                                      # 라벨된 3번 항목은 안 나온다
    assert "[진행 4/5]" in review_out and "커밋 4444444" in review_out
    assert "현재 라벨: relevant" in review_out                               # review에서는 현재 라벨이 보인다
    assert _labels(path)[3] == "unsure"                                      # 이미 준 라벨도 고칠 수 있다
    assert _read(path) == label_cli.set_label_in_text(SAMPLE_TEXT, 3, "unsure")


def test_note_field_is_left_alone(tmp_path):
    path = _write(tmp_path)

    _run(path, ["s", "s", "s", "u", "q"], review=True)

    assert yaml.safe_load(_read(path))["edges"][3]["note"] == "이미 라벨"


# ─── 표시 폭 — 한글·전각은 2칸 ──────────────────────────────────────────────────────────────────

BANNER = ["라벨 큐: eval/edge_labels/queue.yaml", "키를 누를 때마다 바로 저장합니다. ? 를 누르면 도움말.",
          "※ 편집기에서 이 파일을 열어 두었다면 닫고 쓰세요."]
HANGUL_NOTICE = "한글 입력 상태입니다 — 영문으로 바꾼 뒤 r·i·u를 누르세요"
REAL_QUEUE = os.path.join(EVAL_DIR, "edge_labels", "exp-2026-11-05-queue.yaml")


def _korean(n):
    return " ".join(f"문장{i:03d}은 한국어로 길게 이어지는 설명입니다" for i in range(n))


def _long_korean_item():
    """src·dst 모두 길고 변경 파일도 많은 한국어 항목 — 높이에 가장 불리한 모양."""
    return _entry("REFERENCE",
                  _commit("1111111", message=_korean(60), files=[f"services/ai-engine/graph/module_{i:02d}.py" for i in range(15)]),
                  _thread("1.1", [(_korean(20), f"2026-06-26T05:{i:02d}:07Z", "일반", "정세영") for i in range(8)]))


def _split_blocks(lines):
    """render_item 출력을 구분선 기준으로 (src 블록, dst 블록)으로 가른다."""
    sep = next(i for i, line in enumerate(lines) if line.startswith("─"))
    return lines[:sep], lines[sep + 1:]


def test_display_width_counts_wide_chars_as_two_cells_and_combining_marks_as_zero():
    assert label_cli.display_width("") == 0
    assert label_cli.display_width("abc") == 3
    assert label_cli.display_width("한글") == 4
    assert label_cli.display_width("ＡＢ") == 4                 # 전각 영문
    assert label_cli.display_width("a한b") == 4
    assert label_cli.display_width("e\u0301") == 1             # 결합 악센트는 칸을 차지하지 않는다
    assert label_cli.display_width("가\u200d") == 2            # 폭 없는 이음 문자(이모지 조합)도 마찬가지


def test_wrap_text_breaks_at_spaces_by_display_width():
    # 한 줄 10칸 = 한글 5자. "가나다 라마바"는 13칸이라 한 줄에 못 담는다
    assert label_cli.wrap_text("가나다 라마바 사아자", "", "", limit=10) == ["가나다", "라마바", "사아자"]
    assert label_cli.wrap_text("ab cd ef gh", "", "", limit=5) == ["ab cd", "ef gh"]


def test_wrap_text_cuts_unbroken_words_where_the_display_width_fills():
    assert label_cli.wrap_text("가나다라마바사아자차카", "", "", limit=10) == ["가나다라마", "바사아자차", "카"]
    assert label_cli.wrap_text("abc한글def", "> ", "  ", limit=8) == ["> abc한", "  글def"]        # 들여쓰기 포함해 8칸


def test_wrap_text_starts_a_word_too_long_for_any_line_on_the_current_line():
    # 경로가 한 줄(여기서는 24칸)보다 길면 "-"만 덩그러니 남기지 말고 그 줄의 남은 칸부터 채운다
    path = "services/" + "한글경로/" * 12 + "End.java"

    lines = label_cli.wrap_text(f"- {path}", "    ", "      ", limit=30)

    assert lines[0].startswith("    - services/") and display_ok(lines, 30)
    assert "".join(line.strip() for line in lines).replace("- ", "", 1) == path
    # 새 줄에는 들어가는 단어는 여전히 다음 줄로 내려간다
    assert label_cli.wrap_text("ab 한글단어", "", "", limit=8) == ["ab", "한글단어"]


def display_ok(lines, width):
    return max(_cells(line) for line in lines) <= width


def test_wrap_text_keeps_first_and_continuation_indents_and_blank_lines():
    assert label_cli.wrap_text("one two three four", "- ", "    ", limit=12) == ["- one two", "    three", "    four"]
    assert label_cli.wrap_text("    들여쓴 코드", "  ", "  ", limit=40) == ["      들여쓴 코드"]     # 문단 맨 앞 공백은 살린다
    assert label_cli.wrap_text("", "  ", "  ", limit=40) == [""]
    assert label_cli.wrap_text("   ", "  ", "  ", limit=40) == [""]


@pytest.mark.parametrize("width", [40, 60])
def test_korean_and_fullwidth_lines_never_exceed_the_terminal_width(width):
    run = "가나다라마바사아자차카타파하" * 6                       # 공백 없는 긴 한글
    message = run + " 끝 ＡＢＣＤ１２３４ 한영mixed혼합문장입니다 " + "경로/" * 30
    item = _entry("REFERENCE", _commit("1111111", message=message, files=["서비스/" + "한글경로" * 20 + ".py"]),
                  _thread("1.1", [("한글만 이어지는 긴 문장입니다" * 8, "2026-06-26T05:27:07Z", "일반", "정세영")]))

    item_lines = label_cli.render_item(item, width=width, full=True)
    screen = label_cli.render_screen(item, position=1, total=108, remaining=108, full=True, width=width,
                                     height=60, banner=BANNER)

    for lines in (item_lines, screen):
        assert max(_cells(line) for line in lines) <= width
        assert _rows(lines, width) == len(lines)                               # 터미널이 다시 접는 줄이 없다
    assert run in "".join(line.strip() for line in item_lines)                 # 자른 조각을 이으면 본문이 그대로다


# ─── 높이 — 기본(축약) 화면은 터미널 높이에 들어간다 ─────────────────────────────────────────────

@pytest.mark.parametrize("width", [80, 100, 140])
@pytest.mark.parametrize("height", [10, 24, 40])
def test_default_screen_of_a_long_korean_item_fits_the_terminal_height(height, width):
    for banner, label in ((None, None), (BANNER, "relevant")):                  # 가장 높아지는 조합(첫 화면 배너 + 현재 라벨)
        item = {**_long_korean_item(), "label": label}

        lines = label_cli.render_screen(item, position=1, total=108, remaining=108, full=False, width=width,
                                        height=height, banner=banner)

        # 출력 끝의 커서 줄 하나를 남겨야 터미널이 위로 밀리지 않는다
        assert _rows(lines, width) <= height - 1, (height, width, bool(banner))


@pytest.mark.skipif(not os.path.exists(REAL_QUEUE), reason="실제 큐 파일이 없다")
@pytest.mark.parametrize("width", [80, 100, 140])
@pytest.mark.parametrize("height", [10, 24, 40])
def test_every_item_of_the_real_queue_fits_the_default_screen(height, width):
    with open(REAL_QUEUE, encoding="utf-8") as f:                               # 읽기 전용 — 사용자의 실물 파일이다
        items = yaml.safe_load(f)["edges"]

    too_tall = []
    for index, item in enumerate(items):
        for banner, label in ((None, None), (BANNER, "relevant")):
            lines = label_cli.render_screen({**item, "label": label}, position=index + 1, total=len(items),
                                            remaining=len(items), full=False, width=width, height=height, banner=banner)
            if _rows(lines, width) > height - 1:
                too_tall.append((index + 1, bool(banner)))
    assert len(items) > 0 and too_tall == []


def test_session_draws_every_item_screen_within_the_injected_height_and_f_ignores_it(tmp_path):
    path = _write(tmp_path, _queue_text([_long_korean_item() for _ in range(3)]))

    code, out, script = _run(path, ["s", "b", "f", "f", "s", "q"], width=100, height=12)
    screens = [s for s in out.split(CLEAR)[1:] if "[r] relevant" in s]

    # 화면 순서: 0번(s 전) → 1번(s 뒤) → 0번으로 b → f로 전체 → f로 다시 축약 → 1번으로 s
    expanded = screens[3]
    assert len(expanded.rstrip("\n").split("\n")) > 12                          # 전체 보기는 높이와 상관없이 다 보여 준다
    for screen in screens[:3] + screens[4:]:
        assert _rows(screen.rstrip("\n").split("\n"), 100) <= 11


def test_help_is_counted_in_the_row_budget(tmp_path):
    lines = label_cli.render_screen(_long_korean_item(), position=1, total=108, remaining=108, full=False,
                                    width=100, height=40, show_help=True)

    assert any("원문만으로 판단 불가" in line for line in lines)
    assert _rows(lines, 100) <= 39


def test_each_block_keeps_at_least_five_rows_when_both_are_long():
    lines = label_cli.render_item(_long_korean_item(), width=100, full=False, rows=21)

    src, dst = _split_blocks(lines)
    assert len(lines) == 21 and len(src) >= 5 and len(dst) >= 5
    screen = label_cli.render_screen(_long_korean_item(), position=1, total=108, remaining=108, full=False,
                                     width=100, height=24)
    rules = [i for i, line in enumerate(screen) if line.startswith("═")]
    src, dst = _split_blocks(screen[rules[0] + 1:rules[1]])                      # 위·아래 굵은 선 사이 = 두 블록
    assert len(src) >= 5 and len(dst) >= 5


def test_short_block_leaves_its_unused_rows_to_the_long_one():
    item = _entry("REFERENCE", _commit("1111111", message="짧은 메시지", files=[]),
                  _thread("1.1", [(_korean(20), f"2026-06-26T05:{i:02d}:07Z", "일반", "정세영") for i in range(8)]))

    src, dst = _split_blocks(label_cli.render_item(item, width=100, full=False, rows=21))

    assert len(src) == 2                                                         # 헤더 + 한 줄
    assert len(dst) == 21 - 1 - 2                                                # 구분선과 src를 뺀 나머지를 다 쓴다


def test_terminal_height_falls_back_to_24_rows_when_unknown(monkeypatch):
    seen = {}

    def unknown(fallback=(0, 0)):
        seen["fallback"] = fallback
        return os.terminal_size(fallback)

    monkeypatch.setattr(label_cli.shutil, "get_terminal_size", unknown)

    assert label_cli._terminal_height() == 24
    assert seen["fallback"][1] == 24


# ─── 푸터의 진행·질문 ───────────────────────────────────────────────────────────────────────────

def test_footer_repeats_progress_and_question_right_above_the_key_help():
    lines = label_cli.render_screen(_sample_entries()[0], position=3, total=108, remaining=50, full=False,
                                    width=100, height=40)

    keys_at = next(i for i, line in enumerate(lines) if line.startswith("[r] relevant"))
    assert lines[keys_at - 1] == "[3/108] REFERENCE — 이 커밋이 아래 대화(스레드)와 실제로 같은 사안을 다루나?"
    assert "[진행 3/108] 남은 50개 · 유형 REFERENCE" in lines                    # 맨 위 머리는 그대로


def test_footer_keeps_progress_and_question_even_when_the_screen_is_squeezed():
    lines = label_cli.render_screen(_long_korean_item(), position=7, total=108, remaining=100, full=False,
                                    width=100, height=10, banner=BANNER)

    assert "[7/108] REFERENCE — 이 커밋이 아래 대화(스레드)와 실제로 같은 사안을 다루나?" in lines


def test_session_footer_follows_the_item_being_shown(tmp_path):
    path = _write(tmp_path)

    code, out, script = _run(path, ["s", "q"])
    first, second = out.split(CLEAR)[1:3]

    assert "[1/4] REFERENCE — 이 커밋이 아래 대화(스레드)와 실제로 같은 사안을 다루나?" in first
    assert "[2/4] DISCUSSED_IN — 이 이슈가 아래 대화에서 실제로 논의됐나?" in second


# ─── 한글 입력 상태 ─────────────────────────────────────────────────────────────────────────────

@pytest.mark.parametrize("key", ["ㄱ", "ㅑ", "ㅕ", "ㅂ", "가", "힣"])
def test_hangul_input_shows_a_notice_below_the_footer_without_redrawing(tmp_path, key):
    path = _write(tmp_path)

    code, out, script = _run(path, [key, "q"])

    first = out.split(CLEAR)[1]
    assert out.count(CLEAR) == 2                                                 # 첫 화면 + 종료 요약뿐 — 한글 키로 다시 그리지 않았다
    assert HANGUL_NOTICE in first and first.index(HANGUL_NOTICE) > first.index("[?] 도움말")
    assert _read(path) == SAMPLE_TEXT                                            # 자모를 라벨 키로 보지 않는다


def test_non_hangul_unknown_keys_still_show_nothing(tmp_path):
    path = _write(tmp_path)

    code, out, script = _run(path, ["x", "1", "é", "ー", "q"])                    # 일본어 가나·악센트 문자는 한글이 아니다

    assert HANGUL_NOTICE not in out and out.count(CLEAR) == 2


# ─── 마지막 항목 뒤 ─────────────────────────────────────────────────────────────────────────────

def test_after_the_last_item_the_session_waits_for_b_or_q_instead_of_ending(tmp_path):
    path = _write(tmp_path)

    code, out, script = _run(path, ["r", "i", "u", "r", "q"])

    last = out.split(CLEAR)[-1]
    assert script.keys == []                                                     # 마지막 라벨 뒤에도 키를 기다렸다
    assert "마지막까지 봤습니다. 고치려면 b, 끝내려면 q" in last
    assert "모두 끝났습니다" in last and "남은 0개" in last
    assert f"label_queue.py fill --queue {path} <표본 파일들>" in last


def test_skipping_the_last_item_also_waits_and_says_items_were_skipped(tmp_path):
    path = _write(tmp_path)

    code, out, script = _run(path, ["r", "i", "u", "s", "q"])

    last = out.split(CLEAR)[-1]
    assert script.keys == []
    assert "끝까지 봤지만 건너뛴 항목이 있습니다" in last and "모두 끝났습니다" not in last
    assert "마지막까지 봤습니다. 고치려면 b, 끝내려면 q" in last
    assert "남은 1개" in last


def test_b_after_the_last_item_returns_to_it_and_allows_relabeling(tmp_path):
    path = _write(tmp_path)

    code, out, script = _run(path, ["r", "i", "u", "r", "b", "u", "q"])

    assert _labels(path) == ["relevant", "irrelevant", "unsure", "relevant", "unsure"]    # 4번(마지막 대상)을 고쳤다
    back_screen = out.split(CLEAR)[-2]                                           # 끝 화면 바로 앞 = 돌아가서 본 마지막 항목
    assert "커밋 5555555" in back_screen and "현재 라벨: relevant" in back_screen
    assert out.split(CLEAR)[-1].count("unsure 2") == 1                           # 고친 결과로 요약이 다시 나온다
    assert script.keys == []


def test_b_after_skipping_the_last_item_returns_to_that_item(tmp_path):
    path = _write(tmp_path)

    _run(path, ["s", "s", "s", "s", "b", "i", "q"])

    assert _labels(path) == [None, None, None, "relevant", "irrelevant"]


def test_other_keys_on_the_last_screen_are_ignored(tmp_path):
    path = _write(tmp_path)

    code, out, script = _run(path, ["r", "i", "u", "r", "r", "i", "u", "s", "x", "?", "f", "q"])

    assert _labels(path) == ["relevant", "irrelevant", "unsure", "relevant", "relevant"]   # 끝 화면의 r·i·u·s는 라벨이 아니다
    assert script.keys == []
    after_end = script.out_len_at_call[4:]                                       # 끝 화면이 나온 뒤 키를 읽은 시점들
    assert len(set(after_end)) == 1                                              # 그 뒤 모르는 키들은 화면을 건드리지 않는다


@pytest.mark.parametrize("quit_key", ["q", "Q", "\x03", None, KeyboardInterrupt])
def test_q_ctrl_c_and_eof_on_the_last_screen_end_without_a_second_summary(tmp_path, quit_key):
    path = _write(tmp_path)

    code, out, script = _run(path, ["r", "i", "u", "r", quit_key])

    assert code == 0
    assert out.count("남은 0개") == 1                                            # 요약은 끝 화면에 한 번만


def test_hangul_on_the_last_screen_also_shows_the_notice(tmp_path):
    path = _write(tmp_path)

    code, out, script = _run(path, ["r", "i", "u", "r", "ㅂ", "q"])

    assert HANGUL_NOTICE in out.split(CLEAR)[-1]


# ─── .orig 백업 · 저장 검증 ─────────────────────────────────────────────────────────────────────

def test_backup_is_made_on_first_save_and_never_overwritten(tmp_path):
    path = _write(tmp_path)
    assert not os.path.exists(path + ".orig")

    _run(path, ["r", "q"])
    first_backup = _read(path + ".orig")
    _run(path, ["i", "u", "q"])                                              # 같은 파일로 다시 실행, 저장 두 번 더

    assert first_backup == SAMPLE_TEXT                                       # 원본 그대로
    assert _read(path + ".orig") == SAMPLE_TEXT                              # 두 번째 실행·두 번째 저장에서 덮어쓰지 않았다
    assert _read(path) != SAMPLE_TEXT


def test_existing_backup_is_kept(tmp_path):
    path = _write(tmp_path)
    with open(path + ".orig", "w", encoding="utf-8") as f:
        f.write("예전 백업")

    _run(path, ["r", "q"])

    assert _read(path + ".orig") == "예전 백업"


def test_no_backup_is_made_when_nothing_is_saved(tmp_path):
    path = _write(tmp_path)

    _run(path, ["s", "q"])

    assert not os.path.exists(path + ".orig")


def test_save_leaves_no_temp_files_behind(tmp_path):
    path = _write(tmp_path)

    _run(path, ["r", "i", "q"])

    assert sorted(os.listdir(tmp_path)) == ["queue.yaml", "queue.yaml.orig"]


def test_save_keeps_file_permissions(tmp_path):
    path = _write(tmp_path)
    os.chmod(path, 0o640)

    _run(path, ["r", "q"])

    assert (os.stat(path).st_mode & 0o777) == 0o640


def test_verification_failure_leaves_file_untouched_and_stops_with_error(tmp_path, monkeypatch):
    path = _write(tmp_path)
    real = label_cli.set_label_in_text

    def corrupting(text, index, label):
        """올바른 줄을 바꾸되 다른 데이터까지 건드리는 결함 — 검증이 잡아야 한다."""
        return real(text, index, label).replace("원인과 해결 설명", "원인과 해결 설명 (훼손)")

    monkeypatch.setattr(label_cli, "set_label_in_text", corrupting)

    code, out, script = _run(path, ["r", "i", "q"])

    assert code == 1
    assert _read(path) == SAMPLE_TEXT                                        # 파일이 바뀌지 않았다
    assert not os.path.exists(path + ".orig")
    assert script.keys == ["i", "q"]                                         # 오류 뒤에는 키를 더 읽지 않는다
    assert "검증" in out.split(CLEAR)[-1]                                    # 오류가 마지막 화면에 남아 있다


def test_verification_catches_a_label_written_to_the_wrong_item(tmp_path, monkeypatch):
    path = _write(tmp_path)
    real = label_cli.set_label_in_text
    monkeypatch.setattr(label_cli, "set_label_in_text", lambda text, index, label: real(text, index + 1, label))

    code, out, script = _run(path, ["r", "q"])

    assert code == 1 and _read(path) == SAMPLE_TEXT


def test_verify_label_change_passes_for_correct_edit_and_fails_otherwise():
    old_doc = yaml.safe_load(SAMPLE_TEXT)
    good = label_cli.set_label_in_text(SAMPLE_TEXT, 1, "unsure")

    label_cli.verify_label_change(old_doc, good, 1, "unsure")
    with pytest.raises(label_cli.QueueFileError):
        label_cli.verify_label_change(old_doc, good, 0, "unsure")             # 다른 항목이 바뀐 셈
    with pytest.raises(label_cli.QueueFileError):
        label_cli.verify_label_change(old_doc, good, 1, "relevant")           # 다른 값이 들어갔다
    with pytest.raises(label_cli.QueueFileError):
        label_cli.verify_label_change(old_doc, "edges: [", 1, "unsure")       # YAML이 깨졌다


def test_missing_label_line_stops_with_clear_error_and_no_rewrite(tmp_path):
    text = SAMPLE_TEXT.replace("  label: null\n", "", 1)
    path = _write(tmp_path, text)

    code, out, script = _run(path, ["r", "q"])

    assert code == 1
    assert _read(path) == text
    assert "label 줄" in out.split(CLEAR)[-1]


def test_file_changed_by_someone_else_is_not_overwritten(tmp_path):
    path = _write(tmp_path)
    edited = SAMPLE_TEXT + "# 편집기에서 단 메모\n"

    code, out, script = _run_with_hook(path, ["r", "q"], lambda: _write(tmp_path, edited))

    assert code == 1
    assert _read(path) == edited                                             # 편집기에서 저장한 내용이 그대로 남는다
    assert "바뀌" in out.split(CLEAR)[-1]


def _run_with_hook(path, keys, hook):
    out = []
    script = Script(keys, path, out)
    calls = {"n": 0}

    def before():
        calls["n"] += 1
        if calls["n"] == 1:                                                  # 첫 키를 읽기 직전(=화면을 보는 동안)에 외부 수정
            hook()

    script.before_call = before
    code = label_cli.run_session(path, review=False, read_key=script, write=out.append,
                                 get_width=lambda: 80, get_height=lambda: 40)
    return code, "".join(out), script


def test_queue_without_edges_is_rejected(tmp_path):
    path = _write(tmp_path, "seed: 42\n")

    with pytest.raises(label_cli.QueueFileError):
        label_cli.run_session(path, review=False, read_key=lambda: "q", write=lambda s: None, get_width=lambda: 80)


# ─── 터미널 — 한 글자 읽기와 상태 복구 ──────────────────────────────────────────────────────────

try:
    import pty
    import termios
except ImportError:                                                          # Windows — 터미널 읽기 시험만 건너뛴다
    pty = termios = None

needs_pty = pytest.mark.skipif(termios is None, reason="termios/pty가 없는 플랫폼")


def _tty_state(fd):
    """tcgetattr 결과. lflag의 PENDIN은 커널이 모드를 바꿀 때 스스로 세우는 상태 비트라 설정 비교에서 뺀다."""
    state = termios.tcgetattr(fd)
    state[3] &= ~getattr(termios, "PENDIN", 0)
    return state


@pytest.fixture
def pty_pair():
    master, slave = pty.openpty()
    yield master, slave
    for fd in (master, slave):
        try:
            os.close(fd)
        except OSError:
            pass


@needs_pty
def test_cbreak_mode_reads_a_key_without_enter_and_restores_terminal(pty_pair):
    master, slave = pty_pair
    before = _tty_state(slave)

    with label_cli.cbreak_mode(slave):
        assert not termios.tcgetattr(slave)[3] & termios.ICANON              # Enter 없이 읽는 모드
        os.write(master, b"r")
        assert label_cli.read_key_from_fd(slave) == "r"

    assert _tty_state(slave) == before


@needs_pty
def test_cbreak_mode_restores_terminal_even_when_the_body_raises(pty_pair):
    master, slave = pty_pair
    before = _tty_state(slave)

    with pytest.raises(RuntimeError):
        with label_cli.cbreak_mode(slave):
            raise RuntimeError("루프 안에서 터졌다")

    assert _tty_state(slave) == before


@needs_pty
def test_read_key_returns_multibyte_char_whole_and_swallows_escape_sequences(pty_pair):
    master, slave = pty_pair
    with label_cli.cbreak_mode(slave):
        os.write(master, "가".encode("utf-8"))
        assert label_cli.read_key_from_fd(slave) == "가"
        os.write(master, b"\x1b[F")                                          # End 키 — 'F'(전체 보기 토글)로 오해하면 안 된다
        assert label_cli.read_key_from_fd(slave) == ""
        os.write(master, b"R")
        assert label_cli.read_key_from_fd(slave) == "R"


@needs_pty
def test_read_key_returns_none_on_eof(pty_pair):
    master, slave = pty_pair
    with label_cli.cbreak_mode(slave):
        os.close(master)
        assert label_cli.read_key_from_fd(slave) is None


# ─── main — 비TTY 거절 ──────────────────────────────────────────────────────────────────────────

def test_main_refuses_to_run_without_a_tty(tmp_path, monkeypatch, capsys):
    path = _write(tmp_path)
    monkeypatch.setattr(sys, "stdin", io.StringIO(""))

    code = label_cli.main([path])

    captured = capsys.readouterr()
    assert code == 2
    assert "대화형 터미널에서 실행하세요" in captured.out + captured.err
    assert _read(path) == SAMPLE_TEXT and not os.path.exists(path + ".orig")


def test_script_exits_with_code_2_when_stdin_is_not_a_tty(tmp_path):
    path = _write(tmp_path)

    result = subprocess.run([sys.executable, os.path.join(EVAL_DIR, "label_cli.py"), path],
                            stdin=subprocess.DEVNULL, capture_output=True, text=True, encoding="utf-8")

    assert result.returncode == 2
    assert "대화형 터미널에서 실행하세요" in result.stdout + result.stderr
    assert _read(path) == SAMPLE_TEXT
