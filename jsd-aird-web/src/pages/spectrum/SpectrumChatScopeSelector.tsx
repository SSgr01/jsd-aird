import { LineChartOutlined, SearchOutlined } from '@ant-design/icons';
import { useVirtualizer } from '@tanstack/react-virtual';
import { Button, Checkbox, Collapse, Empty, Input, Spin, Typography } from 'antd';
import { useCallback, useEffect, useRef, useState } from 'react';

import { spectrumApi, type SpectrumCategory, type SpectrumChart } from '@/services/spectrum';

const CHARTS_PER_PAGE = 50;
const PREFETCH_ROWS = 8;
const ROW_HEIGHT = 60;

interface ChartPageState {
  items: SpectrumChart[];
  page: number;
  total: number;
  loading: boolean;
  error?: string;
}

interface SearchPageState extends ChartPageState {
  query: string;
  requestId: number;
}

interface SpectrumChatScopeSelectorProps {
  categories: SpectrumCategory[];
  categoriesLoading: boolean;
  selectedCharts: string[];
  onSelectChart: (chart: SpectrumChart, checked: boolean) => void;
  onOpenPagePicker: (chart: SpectrumChart) => void;
}

const emptyChartPage = (): ChartPageState => ({
  items: [],
  page: 0,
  total: 0,
  loading: false,
});

function mergeCharts(current: SpectrumChart[], incoming: SpectrumChart[]) {
  const byId = new Map(current.map((chart) => [chart.id, chart]));
  incoming.forEach((chart) => byId.set(chart.id, chart));
  return [...byId.values()];
}

function VirtualizedChartRows({
  items,
  total,
  loading,
  error,
  showCategory,
  onLoadMore,
  onRetry,
  selectedCharts,
  onSelectChart,
  onOpenPagePicker,
}: {
  items: SpectrumChart[];
  total: number;
  loading: boolean;
  error?: string;
  showCategory?: boolean;
  onLoadMore: () => void;
  onRetry: () => void;
  selectedCharts: string[];
  onSelectChart: (chart: SpectrumChart, checked: boolean) => void;
  onOpenPagePicker: (chart: SpectrumChart) => void;
}) {
  const scrollElement = useRef<HTMLDivElement>(null);
  const virtualizer = useVirtualizer({
    count: items.length,
    getScrollElement: () => scrollElement.current,
    initialRect: { width: 360, height: 260 },
    initialOffset: 0,
    estimateSize: () => ROW_HEIGHT,
    overscan: 8,
  });
  const measuredRows = virtualizer.getVirtualItems();
  const virtualRows = measuredRows.length
    ? measuredRows
    : items.slice(0, 12).map((_, index) => ({
        index,
        key: index,
        start: index * ROW_HEIGHT,
        size: ROW_HEIGHT,
      }));
  const lastVisibleIndex = virtualRows.at(-1)?.index ?? -1;
  const hasMore = items.length < total;

  useEffect(() => {
    if (hasMore && !loading && lastVisibleIndex >= items.length - PREFETCH_ROWS) onLoadMore();
  }, [hasMore, items.length, lastVisibleIndex, loading, onLoadMore]);

  return (
    <>
      <div className="spectrum-chat-chart-list" ref={scrollElement}>
        {items.length ? (
          <div
            className="spectrum-chat-chart-list-inner"
            style={{ height: virtualizer.getTotalSize() }}
          >
            {virtualRows.map((virtualRow) => {
              const chart = items[virtualRow.index];
              if (!chart) return null;
              return (
                <div
                  key={chart.id}
                  className={`spectrum-chat-chart-option${selectedCharts.includes(chart.id) ? ' is-selected' : ''}`}
                  style={{
                    height: virtualRow.size,
                    transform: `translateY(${virtualRow.start}px)`,
                  }}
                >
                  <Checkbox
                    checked={selectedCharts.includes(chart.id)}
                    onChange={(event) => onSelectChart(chart, event.target.checked)}
                  >
                    <span className="spectrum-chat-chart-copy">
                      <Typography.Text ellipsis={{ tooltip: chart.title }}>
                        {chart.title}
                      </Typography.Text>
                      <Typography.Text type="secondary" ellipsis={{ tooltip: chart.originalName }}>
                        {showCategory
                          ? `${chart.categoryName} · ${chart.originalName}`
                          : chart.originalName}
                      </Typography.Text>
                    </span>
                  </Checkbox>
                  <Button type="link" size="small" onClick={() => onOpenPagePicker(chart)}>
                    选页
                  </Button>
                </div>
              );
            })}
          </div>
        ) : loading ? (
          <div className="spectrum-chat-chart-list-state">
            <Spin size="small" />
            <Typography.Text type="secondary">正在加载图谱</Typography.Text>
          </div>
        ) : error ? (
          <div className="spectrum-chat-chart-list-state">
            <Typography.Text type="secondary">图谱加载失败</Typography.Text>
            <Button size="small" onClick={onRetry}>
              重试
            </Button>
          </div>
        ) : (
          <Empty image={Empty.PRESENTED_IMAGE_SIMPLE} description="没有找到图谱" />
        )}
      </div>
      {loading && items.length ? (
        <div className="spectrum-chat-chart-list-more" aria-live="polite">
          <Spin size="small" />
          <Typography.Text type="secondary">正在加载更多图谱</Typography.Text>
        </div>
      ) : error && items.length ? (
        <div className="spectrum-chat-chart-list-more">
          <Typography.Text type="secondary">后续图谱加载失败</Typography.Text>
          <Button size="small" onClick={onRetry}>
            重试
          </Button>
        </div>
      ) : hasMore ? null : items.length ? (
        <Typography.Text className="spectrum-chat-chart-list-complete" type="secondary">
          已显示全部 {total} 张图谱
        </Typography.Text>
      ) : null}
    </>
  );
}

export function SpectrumChatScopeSelector({
  categories,
  categoriesLoading,
  selectedCharts,
  onSelectChart,
  onOpenPagePicker,
}: SpectrumChatScopeSelectorProps) {
  const [keyword, setKeyword] = useState('');
  const [openCategoryIds, setOpenCategoryIds] = useState<string[]>([]);
  const [categoryPages, setCategoryPages] = useState<Record<string, ChartPageState>>({});
  const categoryPagesRef = useRef(categoryPages);
  const categoryRequestsRef = useRef(new Set<string>());
  const [searchPage, setSearchPage] = useState<SearchPageState>({
    ...emptyChartPage(),
    query: '',
    requestId: 0,
  });
  const searchPageRef = useRef(searchPage);
  const searchRequestIdRef = useRef(0);
  const searchRequestsRef = useRef(new Set<string>());

  const updateCategoryPage = useCallback(
    (categoryId: string, update: (current: ChartPageState) => ChartPageState) => {
      const current = categoryPagesRef.current[categoryId] || emptyChartPage();
      const next = { ...categoryPagesRef.current, [categoryId]: update(current) };
      categoryPagesRef.current = next;
      setCategoryPages(next);
    },
    [],
  );

  const loadCategoryPage = useCallback(
    async (categoryId: string, page: number) => {
      if (categoryRequestsRef.current.has(categoryId)) return;
      categoryRequestsRef.current.add(categoryId);
      updateCategoryPage(categoryId, (current) => ({
        ...current,
        loading: true,
        error: undefined,
      }));
      try {
        const result = await spectrumApi.listCharts({
          categoryId,
          page,
          size: CHARTS_PER_PAGE,
        });
        updateCategoryPage(categoryId, (current) => ({
          items: page === 1 ? result.items : mergeCharts(current.items, result.items),
          page: result.page || page,
          total: result.total ?? result.items.length,
          loading: false,
        }));
      } catch {
        updateCategoryPage(categoryId, (current) => ({
          ...current,
          loading: false,
          error: '图谱加载失败',
        }));
      } finally {
        categoryRequestsRef.current.delete(categoryId);
      }
    },
    [updateCategoryPage],
  );

  const loadMoreCategory = useCallback(
    (categoryId: string) => {
      const current = categoryPagesRef.current[categoryId] || emptyChartPage();
      if (current.loading || categoryRequestsRef.current.has(categoryId)) return;
      if (current.page && current.items.length >= current.total) return;
      void loadCategoryPage(categoryId, current.page + 1);
    },
    [loadCategoryPage],
  );

  const loadSearchPage = useCallback(async (query: string, page: number, requestId: number) => {
    const requestKey = `${requestId}:${page}`;
    if (searchRequestsRef.current.has(requestKey)) return;
    searchRequestsRef.current.add(requestKey);
    searchPageRef.current = {
      ...searchPageRef.current,
      query,
      requestId,
      loading: true,
      error: undefined,
    };
    setSearchPage(searchPageRef.current);
    try {
      const result = await spectrumApi.listCharts({
        keyword: query,
        page,
        size: CHARTS_PER_PAGE,
      });
      if (searchRequestIdRef.current !== requestId) return;
      const current = searchPageRef.current;
      searchPageRef.current = {
        query,
        requestId,
        items: page === 1 ? result.items : mergeCharts(current.items, result.items),
        page: result.page || page,
        total: result.total ?? result.items.length,
        loading: false,
      };
      setSearchPage(searchPageRef.current);
    } catch {
      if (searchRequestIdRef.current !== requestId) return;
      searchPageRef.current = {
        ...searchPageRef.current,
        query,
        requestId,
        loading: false,
        error: '搜索图谱失败',
      };
      setSearchPage(searchPageRef.current);
    } finally {
      searchRequestsRef.current.delete(requestKey);
    }
  }, []);

  useEffect(() => {
    const query = keyword.trim();
    const requestId = ++searchRequestIdRef.current;
    if (!query) {
      const cleared: SearchPageState = { ...emptyChartPage(), query: '', requestId };
      searchPageRef.current = cleared;
      setSearchPage(cleared);
      return;
    }

    const initial: SearchPageState = {
      ...emptyChartPage(),
      query,
      requestId,
      loading: true,
    };
    searchPageRef.current = initial;
    setSearchPage(initial);
    const timeout = window.setTimeout(() => void loadSearchPage(query, 1, requestId), 250);
    return () => window.clearTimeout(timeout);
  }, [keyword, loadSearchPage]);

  const loadMoreSearch = useCallback(() => {
    const current = searchPageRef.current;
    if (
      !current.query ||
      current.query !== keyword.trim() ||
      current.loading ||
      current.items.length >= current.total
    )
      return;
    void loadSearchPage(current.query, current.page + 1, current.requestId);
  }, [keyword, loadSearchPage]);

  const handleCategoryChange = (keys: string | string[]) => {
    const nextOpenIds = Array.isArray(keys) ? keys : [keys];
    setOpenCategoryIds(nextOpenIds);
    nextOpenIds.forEach((categoryId) => {
      if (!categoryPagesRef.current[categoryId]) void loadCategoryPage(categoryId, 1);
    });
  };

  const visibleCategories = categories.filter((category) => category.chartCount > 0);
  const searching = Boolean(keyword.trim());

  return (
    <div className="spectrum-chat-scope-content">
      <Input
        className="spectrum-chat-scope-search"
        allowClear
        prefix={<SearchOutlined />}
        placeholder="搜索图谱、样品、批号"
        value={keyword}
        onChange={(event) => setKeyword(event.target.value)}
      />
      <div className="spectrum-chat-scope-summary">
        <span>当前引用</span>
        <strong>{selectedCharts.length}</strong>
        <Typography.Text type="secondary">张图谱，可跨分类多选</Typography.Text>
      </div>
      {categoriesLoading ? (
        <div className="spectrum-chat-scope-state">
          <Spin size="small" />
          <Typography.Text type="secondary">正在加载分类</Typography.Text>
        </div>
      ) : searching ? (
        <div className="spectrum-chat-search-results">
          <Typography.Text className="spectrum-chat-search-count" type="secondary">
            {searchPage.query !== keyword.trim() || searchPage.loading
              ? '正在搜索全部图谱'
              : searchPage.error
                ? '搜索失败'
                : `搜索到 ${searchPage.total} 张图谱`}
          </Typography.Text>
          {searchPage.error && !searchPage.items.length ? (
            <div className="spectrum-chat-scope-state">
              <Typography.Text type="secondary">{searchPage.error}</Typography.Text>
              <Button
                size="small"
                onClick={() => void loadSearchPage(keyword.trim(), 1, searchPage.requestId)}
              >
                重试
              </Button>
            </div>
          ) : (
            <VirtualizedChartRows
              items={searchPage.query === keyword.trim() ? searchPage.items : []}
              total={searchPage.total}
              loading={searchPage.loading || searchPage.query !== keyword.trim()}
              error={searchPage.error}
              showCategory
              onLoadMore={loadMoreSearch}
              onRetry={() =>
                void loadSearchPage(keyword.trim(), searchPage.page + 1, searchPage.requestId)
              }
              selectedCharts={selectedCharts}
              onSelectChart={onSelectChart}
              onOpenPagePicker={onOpenPagePicker}
            />
          )}
        </div>
      ) : visibleCategories.length ? (
        <Collapse
          className="spectrum-chat-category-list"
          ghost
          activeKey={openCategoryIds}
          onChange={handleCategoryChange}
          items={visibleCategories.map((category) => {
            const chartPage = categoryPages[category.id];
            return {
              key: category.id,
              label: (
                <span className="spectrum-chat-category-label">
                  <span>
                    <LineChartOutlined />
                    {category.name}
                  </span>
                  <b>{category.chartCount}</b>
                </span>
              ),
              children: (
                <VirtualizedChartRows
                  items={chartPage?.items || []}
                  total={chartPage?.total ?? category.chartCount}
                  loading={chartPage?.loading || false}
                  error={chartPage?.error}
                  onLoadMore={() => loadMoreCategory(category.id)}
                  onRetry={() => loadMoreCategory(category.id)}
                  selectedCharts={selectedCharts}
                  onSelectChart={onSelectChart}
                  onOpenPagePicker={onOpenPagePicker}
                />
              ),
            };
          })}
        />
      ) : (
        <div className="spectrum-chat-scope-state">
          <Empty image={Empty.PRESENTED_IMAGE_SIMPLE} description="暂无可选图谱，请先上传" />
        </div>
      )}
    </div>
  );
}
