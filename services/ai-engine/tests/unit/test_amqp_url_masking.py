"""AMQP URL 자격증명 마스킹 단위 테스트 (오프라인).

배경: RabbitMQ 비밀번호를 .env로 설정할 수 있게 되면서, 연결 URL을 그대로 로그에 찍던
기존 코드가 비밀번호를 원문으로 남기게 됐다(PR #109 봇 리뷰 지적). 로그에 남는 것은
사용자명까지이고 비밀번호는 절대 나가지 않아야 한다.
"""

import pytest

from graph.consumer import mask_amqp_url


@pytest.mark.parametrize(
    "url, expected",
    [
        # 자격증명이 있으면 비밀번호만 가린다 — 사용자명은 어느 계정으로 붙는지 진단에 필요하다
        ("amqp://guest:guest@rabbitmq:5672/", "amqp://guest:***@rabbitmq:5672/"),
        ("amqp://svc:s3cr3t@host/vhost", "amqp://svc:***@host/vhost"),
        # 자격증명이 없으면 그대로 둔다
        ("amqp://rabbitmq:5672/", "amqp://rabbitmq:5672/"),
        # 사용자명만 있고 비밀번호가 없으면 콜론을 만들지 않는다
        ("amqp://guest@rabbitmq:5672/", "amqp://guest@rabbitmq:5672/"),
        # 스킴이 없는 값은 건드리지 않는다(잘못된 설정이어도 로그는 남아야 한다)
        ("rabbitmq:5672", "rabbitmq:5672"),
    ],
)
def test_비밀번호를_가리고_나머지는_보존한다(url, expected):
    assert mask_amqp_url(url) == expected


def test_비밀번호에_특수문자가_있어도_원문이_남지_않는다():
    """URL-safe하지 않은 값이 들어와도 마스킹은 뚫리지 않아야 한다.

    '@'가 비밀번호에 들어가면 호스트 경계가 모호해지는데, 이때도 원문 조각이
    로그로 새면 안 된다. rpartition으로 마지막 '@'를 경계로 삼는 이유다.
    """
    masked = mask_amqp_url("amqp://user:p@ss/word@rabbitmq:5672/")
    assert "p@ss/word" not in masked
    assert masked.startswith("amqp://user:***@")
