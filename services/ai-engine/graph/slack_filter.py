import re

# 이모지만 / 단순 반응
_TRIVIAL_PATTERNS = re.compile(
    r"^[\s\U0001F300-\U0001FFFF\u2600-\u26FF\u2700-\u27BF]+$"  # 이모지만
    r"|^(ㅋ+|ㅎ+|ㅇㅇ|ㄴㄴ|ㅇㅋ|ㄱㄱ|ㄷㄷ|ㄹㅇ|응|아|넹|넵|넵넵|예|ㅇ|ㄴ|ㅋ|ㄱ)+$"
    r"|^(ok|ㅇㅋ|lol|gg|👍|👌|✅|🙏)+$",
    re.IGNORECASE
)

# Slack 시스템 메시지
_SYSTEM_PATTERNS = re.compile(
    r"^<@\w+> has (joined|left) the channel"
    r"|^This channel was (created|archived)"
    r"|채널에 (참여|입장|퇴장)",
    re.IGNORECASE
)

# URL만 있는 메시지
_URL_ONLY = re.compile(r"^[\s<]*(https?://\S+)[\s>]*$")

# 멘션만 있는 메시지 (<@U12345> 형태만)
_MENTION_ONLY = re.compile(r"^[\s]*(<@[A-Z0-9]+>[\s]*)+$")

# 반복 문자 (ㅋㅋㅋ, ..., !!! 등)
_REPEATED_CHARS = re.compile(r"^(.)\1{2,}$")

# 파일 첨부만 있는 메시지 (텍스트 없음)
_FILE_ONLY = re.compile(r"^[\s]*<[^>]+\.(pdf|png|jpg|jpeg|gif|zip|xlsx|docx|pptx|txt|csv)>[\s]*$", re.IGNORECASE)


MIN_LENGTH = 7

def should_skip_slack(body: str) -> bool:
    if not body or not body.strip():
        return True
    body = body.strip()
    if len(body) < MIN_LENGTH:
        return True
    if _TRIVIAL_PATTERNS.match(body):
        return True
    if _SYSTEM_PATTERNS.search(body):
        return True
    if _URL_ONLY.match(body):
        return True
    if _MENTION_ONLY.match(body):
        return True
    if _REPEATED_CHARS.match(body):
        return True
    if _FILE_ONLY.match(body):
        return True
    return False
