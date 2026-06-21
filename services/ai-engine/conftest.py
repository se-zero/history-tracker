"""Pytest bootstrap shared across the ai-engine test suite.

Several modules construct an OpenAI client at import time from
``os.environ["OPENAI_API_KEY"]`` (e.g. ``graph/embedder.py``,
``graph/actor_llm.py``, ``agent/orchestrator.py``). Provide a dummy key so the
offline unit suite can import these modules without a real credential.
``setdefault`` leaves any real key untouched for integration runs.
"""

import os

os.environ.setdefault("OPENAI_API_KEY", "sk-test-dummy-key-not-used")
