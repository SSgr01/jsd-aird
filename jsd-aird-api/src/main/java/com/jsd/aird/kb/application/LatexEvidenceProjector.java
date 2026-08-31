package com.jsd.aird.kb.application;

import java.io.StringReader;
import java.util.Set;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;

import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.xml.sax.InputSource;
import uk.ac.ed.ph.snuggletex.SnuggleEngine;
import uk.ac.ed.ph.snuggletex.SnuggleInput;

/** Converts simple LaTeX ASTs to readable evidence and preserves complex expressions losslessly. */
public final class LatexEvidenceProjector {

    private static final Set<String> TRANSPARENT = Set.of(
            "math", "mrow", "mstyle", "semantics", "mpadded");
    private static final Set<String> LEAF = Set.of("mi", "mn", "mo", "mtext", "ms");

    public String project(String latexRaw) {
        var renderLatex = MineruLatexNormalizer.normalizeForRender(latexRaw);
        if (renderLatex.isBlank()) return "";
        try {
            var session = new SnuggleEngine().createSession();
            if (!session.parseInput(new SnuggleInput("$" + renderLatex + "$"))) return fallback(renderLatex);
            var xml = session.buildXMLString();
            var factory = DocumentBuilderFactory.newInstance();
            factory.setNamespaceAware(true);
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
            factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
            factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
            factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
            var document = factory.newDocumentBuilder().parse(new InputSource(new StringReader("<root>" + xml + "</root>")));
            var maths = document.getElementsByTagNameNS("http://www.w3.org/1998/Math/MathML", "math");
            if (maths.getLength() != 1) return fallback(renderLatex);
            var projected = projectNode(maths.item(0));
            if (!projected.safe() || projected.text().isBlank()) return fallback(renderLatex);
            return projected.text().replaceAll("[ \\t]+", " ").strip();
        } catch (Exception ignored) {
            return fallback(renderLatex);
        }
    }

    private Projection projectNode(Node node) {
        if (node.getNodeType() == Node.TEXT_NODE) return new Projection(node.getNodeValue(), true);
        if (node.getNodeType() != Node.ELEMENT_NODE) return new Projection("", true);
        var name = node.getLocalName() == null ? node.getNodeName() : node.getLocalName();
        if ("annotation".equals(name) || "annotation-xml".equals(name)) return new Projection("", true);
        if (LEAF.contains(name)) return new Projection(node.getTextContent(), true);
        if ("mspace".equals(name)) return new Projection(" ", true);
        if ("msup".equals(name) || "msub".equals(name)) return projectScript((Element) node, "msup".equals(name));
        if ("msubsup".equals(name)) return projectSubSup((Element) node);
        if (!TRANSPARENT.contains(name)) return new Projection("", false);
        return projectChildren(node);
    }

    private Projection projectChildren(Node node) {
        var text = new StringBuilder();
        for (var child = node.getFirstChild(); child != null; child = child.getNextSibling()) {
            var projected = projectNode(child);
            if (!projected.safe()) return projected;
            text.append(projected.text());
        }
        return new Projection(text.toString(), true);
    }

    private Projection projectScript(Element element, boolean superscript) {
        var children = elementChildren(element);
        if (children.size() != 2) return new Projection("", false);
        var base = projectNode(children.get(0));
        var script = projectNode(children.get(1));
        if (!base.safe() || !script.safe()) return new Projection("", false);
        var unicode = unicodeScript(script.text(), superscript);
        return unicode == null ? new Projection("", false) : new Projection(base.text() + unicode, true);
    }

    private Projection projectSubSup(Element element) {
        var children = elementChildren(element);
        if (children.size() != 3) return new Projection("", false);
        var base = projectNode(children.get(0));
        var sub = projectNode(children.get(1));
        var sup = projectNode(children.get(2));
        if (!base.safe() || !sub.safe() || !sup.safe()) return new Projection("", false);
        var unicodeSub = unicodeScript(sub.text(), false);
        var unicodeSup = unicodeScript(sup.text(), true);
        return unicodeSub == null || unicodeSup == null ? new Projection("", false)
                : new Projection(base.text() + unicodeSub + unicodeSup, true);
    }

    private java.util.List<Node> elementChildren(Element element) {
        var result = new java.util.ArrayList<Node>();
        for (var child = element.getFirstChild(); child != null; child = child.getNextSibling()) {
            if (child.getNodeType() == Node.ELEMENT_NODE) result.add(child);
        }
        return result;
    }

    static String unicodeScript(String value, boolean superscript) {
        if (value == null) return null;
        var source = value.replaceAll("\\s+", "");
        var plain = superscript ? "0123456789+-=()n" : "0123456789+-=()aeoxhklmnpst";
        var mapped = superscript ? "⁰¹²³⁴⁵⁶⁷⁸⁹⁺⁻⁼⁽⁾ⁿ" : "₀₁₂₃₄₅₆₇₈₉₊₋₌₍₎ₐₑₒₓₕₖₗₘₙₚₛₜ";
        var result = new StringBuilder();
        for (var index = 0; index < source.length(); index++) {
            var found = plain.indexOf(source.charAt(index));
            if (found < 0) return null;
            result.append(mapped.charAt(found));
        }
        return result.toString();
    }

    private String fallback(String renderLatex) { return "$" + renderLatex + "$"; }

    private record Projection(String text, boolean safe) { }
}
