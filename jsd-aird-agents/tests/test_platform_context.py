from langchain_core.messages import AIMessage, HumanMessage

from jsd_aird_agents.app.platform.context import recent_context, summarization_middleware


def test_pack_switch_filters_prior_module_context_before_trimming():
    messages = [
        HumanMessage(content="项目内部记录"),
        AIMessage(content="项目延期事实", additional_kwargs={"pack_ids": ["project"]}),
        HumanMessage(content="项目质量联合信息", additional_kwargs={"pack_ids": ["project", "quality"]}),
        HumanMessage(content="本轮质量问题", additional_kwargs={"pack_ids": ["quality"]}),
    ]
    assert recent_context(messages, pack_ids=["quality"]) == [{"role": "human", "content": "本轮质量问题"}]
    combined = recent_context(messages, pack_ids=["project", "quality"])
    assert len(combined) == 4


def test_context_budget_uses_langchain_trimming_and_native_summary_adapter():
    from langchain.agents.middleware import SummarizationMiddleware
    from langchain_core.language_models.fake_chat_models import FakeListChatModel

    messages = [
        HumanMessage(content="a" * 500),
        AIMessage(content="b" * 500),
        HumanMessage(content="最近问题"),
    ]
    assert recent_context(messages, max_tokens=10) == [{"role": "human", "content": "最近问题"}]
    middleware = summarization_middleware(FakeListChatModel(responses=["summary"]))
    assert isinstance(middleware, SummarizationMiddleware)
