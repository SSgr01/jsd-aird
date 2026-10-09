package com.jsd.aird.rnd.application;

import com.jsd.aird.kb.domain.DocumentParser;
import com.jsd.aird.kb.domain.OrderedDocumentProjector;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/** Joins a photographed table that MinerU split at a page seam. */
final class ExperimentImageTableJoiner {
    private static final Pattern SAMPLE_NUMBER = Pattern.compile("^\\s*(\\d+)\\s*[#＃]\\s*$");

    OrderedDocumentProjector.Projection join(List<DocumentParser.TextBlock> blocks,
                                              OrderedDocumentProjector.Projection projection) {
        var bounds = new HashMap<String, TableBounds>();
        var pages = new HashMap<String, Integer>();
        if (blocks != null) for (var block : blocks) {
            var group = block.attributes().get("tableGroup");
            if (group != null && !bounds.containsKey(String.valueOf(group))) {
                var box = TableBounds.from(block);
                if (box != null) bounds.put(String.valueOf(group), box);
            }
            if (group != null && block.pageNo() != null) pages.putIfAbsent(String.valueOf(group), block.pageNo());
        }
        var sections = new ArrayList<>(projection.sections());
        for (var first = 0; first < sections.size(); first++) {
            if (!(sections.get(first) instanceof OrderedDocumentProjector.Table one)) continue;
            for (var second = first + 1; second < sections.size(); second++) {
                if (!(sections.get(second) instanceof OrderedDocumentProjector.Table two)) continue;
                var oneBox = bounds.get(one.group());
                var twoBox = bounds.get(two.group());
                var continuation = sampleContinuation(one, two, pages);
                OrderedDocumentProjector.Table left;
                OrderedDocumentProjector.Table right;
                int rowShift;
                if (continuation != null) {
                    left = continuation.left();
                    right = continuation.right();
                    rowShift = continuation.rowShift();
                } else if (sideBySide(one, oneBox, two, twoBox)) {
                    left = oneBox.left() < twoBox.left() ? one : two;
                    right = left == one ? two : one;
                    rowShift = 0;
                } else continue;
                sections.set(first, merge(left, right, rowShift));
                sections.remove(second);
                break;
            }
        }
        var tables = sections.stream().filter(OrderedDocumentProjector.Table.class::isInstance)
                .map(OrderedDocumentProjector.Table.class::cast).toList();
        var score = tables.isEmpty() ? 0d : tables.stream()
                .mapToDouble(OrderedDocumentProjector.Table::structureScore).average().orElse(0d);
        return new OrderedDocumentProjector.Projection(List.copyOf(sections), score, projection.issues());
    }

    private Continuation sampleContinuation(OrderedDocumentProjector.Table one,
                                            OrderedDocumentProjector.Table two,
                                            Map<String, Integer> pages) {
        var onePage = pages.get(one.group());
        var twoPage = pages.get(two.group());
        if (onePage != null && twoPage != null && !onePage.equals(twoPage)) return null;
        if (Math.abs(one.rowCount() - two.rowCount()) > 2) return null;
        var first = sampleHeader(one);
        var second = sampleHeader(two);
        if (first == null || second == null) return null;
        if (first.last() + 1 == second.first()) {
            return new Continuation(one, two, first.row() - second.row());
        }
        if (second.last() + 1 == first.first()) {
            return new Continuation(two, one, second.row() - first.row());
        }
        return null;
    }

    private SampleHeader sampleHeader(OrderedDocumentProjector.Table table) {
        var rows = new HashMap<Integer, List<OrderedDocumentProjector.Cell>>();
        for (var cell : table.cells()) rows.computeIfAbsent(cell.row(), ignored -> new ArrayList<>()).add(cell);
        for (var row : rows.entrySet()) {
            var cells = row.getValue().stream()
                    .sorted(java.util.Comparator.comparingInt(OrderedDocumentProjector.Cell::column)).toList();
            var numbers = new ArrayList<Integer>();
            for (var cell : cells) {
                var match = SAMPLE_NUMBER.matcher(cell.text());
                if (match.matches()) numbers.add(Integer.parseInt(match.group(1)));
            }
            if (numbers.size() < 2) continue;
            var consecutive = true;
            for (var index = 1; index < numbers.size(); index++) {
                if (numbers.get(index) != numbers.get(index - 1) + 1) consecutive = false;
            }
            if (consecutive) return new SampleHeader(row.getKey(), numbers.getFirst(), numbers.getLast());
        }
        return null;
    }

    private boolean sideBySide(OrderedDocumentProjector.Table one, TableBounds a,
                               OrderedDocumentProjector.Table two, TableBounds b) {
        if (a == null || b == null || a.pageNo() == null || !a.pageNo().equals(b.pageNo())) return false;
        if (one.rowCount() < 4 || one.rowCount() != two.rowCount()) return false;
        var left = a.left() < b.left() ? a : b;
        var right = left == a ? b : a;
        var gap = right.left() - left.right();
        var overlap = Math.min(a.bottom(), b.bottom()) - Math.max(a.top(), b.top());
        var smallerHeight = Math.min(a.bottom() - a.top(), b.bottom() - b.top());
        return gap >= -0.02 && gap <= 0.12 && smallerHeight > 0
                && overlap / smallerHeight >= 0.8
                && Math.abs(a.top() - b.top()) <= 0.08
                && Math.abs(a.bottom() - b.bottom()) <= 0.08;
    }

    private OrderedDocumentProjector.Table merge(OrderedDocumentProjector.Table left,
                                                  OrderedDocumentProjector.Table right, int rowShift) {
        var leftOffset = Math.max(0, -rowShift);
        var rightOffset = leftOffset + rowShift;
        var cells = new ArrayList<OrderedDocumentProjector.Cell>();
        for (var cell : left.cells()) {
            cells.add(new OrderedDocumentProjector.Cell(cell.text(), cell.row() + leftOffset,
                    cell.column(), cell.rowSpan(), cell.columnSpan(), cell.header(), cell.geometry()));
        }
        for (var cell : right.cells()) {
            cells.add(new OrderedDocumentProjector.Cell(cell.text(), cell.row() + rightOffset,
                    cell.column() + left.columnCount(), cell.rowSpan(), cell.columnSpan(),
                    cell.header(), cell.geometry()));
        }
        var abnormal = new HashSet<Integer>();
        left.abnormalRows().forEach(row -> abnormal.add(row + leftOffset));
        right.abnormalRows().forEach(row -> abnormal.add(row + rightOffset));
        return new OrderedDocumentProjector.Table(left.group() + "+" + right.group(),
                List.copyOf(cells), Math.max(left.rowCount() + leftOffset, right.rowCount() + rightOffset),
                left.columnCount() + right.columnCount(),
                Math.min(left.structureScore(), right.structureScore()),
                abnormal.stream().sorted().toList());
    }

    private record SampleHeader(int row, int first, int last) { }
    private record Continuation(OrderedDocumentProjector.Table left,
                                OrderedDocumentProjector.Table right, int rowShift) { }

    private record TableBounds(Integer pageNo, double left, double top, double right, double bottom) {
        static TableBounds from(DocumentParser.TextBlock block) {
            var polygon = block.bbox();
            if (polygon == null || polygon.size() < 8) return null;
            var left = 1d;
            var top = 1d;
            var right = 0d;
            var bottom = 0d;
            for (var index = 0; index + 1 < polygon.size(); index += 2) {
                left = Math.min(left, polygon.get(index));
                right = Math.max(right, polygon.get(index));
                top = Math.min(top, polygon.get(index + 1));
                bottom = Math.max(bottom, polygon.get(index + 1));
            }
            return new TableBounds(block.pageNo(), left, top, right, bottom);
        }
    }
}
