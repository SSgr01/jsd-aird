import pytest

from aird_agent.hosts.jsd.bindings import JsdBindings


@pytest.mark.parametrize('query', ['load_workspace_dependencies', '不存在的专有工具xyz'])
async def test_unknown_native_tool_does_not_activate_enterprise_catalog(query):
    bindings = JsdBindings()
    bindings.tools_for = lambda run: [
        {'name': 'search_experiments', 'description': '搜索授权实验'},
        {'name': 'create_experiment_draft', 'description': '创建可编辑实验草稿'},
    ]
    result = await bindings.dispatch(None, {}, {}, 'tool_search', {'query': query}, 'discovery')
    assert result == {'tools': [], 'activate_tools': []}


async def test_chinese_experiment_request_discovers_relevant_tools_without_all_tools_fallback():
    bindings = JsdBindings()
    bindings.tools_for = lambda run: [
        {'name': 'search_experiments', 'description': '搜索授权实验'},
        {'name': 'create_experiment_draft', 'description': '创建可编辑实验草稿'},
        {'name': 'report_save', 'description': '保存正式报告'},
    ]
    result = await bindings.dispatch(None, {}, {}, 'tool_search', {'query': '查询实验并创建草稿'}, 'discovery')
    assert set(result['activate_tools']) == {'search_experiments', 'create_experiment_draft'}
