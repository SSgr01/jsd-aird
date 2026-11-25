package com.jsd.aird.shared.office;

import java.io.ByteArrayOutputStream;
import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.transform.TransformerFactory;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.stream.StreamResult;
import org.apache.poi.openxml4j.opc.OPCPackage;
import org.w3c.dom.Element;

/** Works around POI's prefix-sensitive custom table-style reader without changing style data. */
final class XlsxStyleNamespaceCompatibility {
    private static final String NS="http://schemas.openxmlformats.org/spreadsheetml/2006/main";
    private XlsxStyleNamespaceCompatibility() { }

    static void prepare(OPCPackage pack) throws Exception {
        for(var part:pack.getPartsByContentType("application/vnd.openxmlformats-officedocument.spreadsheetml.styles+xml")) {
            var factory=DocumentBuilderFactory.newDefaultInstance();
            factory.setNamespaceAware(true);
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl",true);
            factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD,"");
            factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA,"");
            org.w3c.dom.Document xml;
            try(var input=part.getInputStream()){xml=factory.newDocumentBuilder().parse(input);}
            if(xml.getElementsByTagNameNS(NS,"tableStyle").getLength()==0) continue;
            var formats=xml.getElementsByTagNameNS(NS,"dxfs");
            boolean changed=false;
            for(int i=0;i<formats.getLength();i++) {
                var node=(Element)formats.item(i);
                if(node.getPrefix()==null || "x".equals(node.getPrefix())) continue;
                // XSSFTableStyle accepts only parent node names dxfs/x:dxfs,
                // even when another prefix denotes the identical namespace.
                node.setPrefix("x");
                node.setAttributeNS(XMLConstants.XMLNS_ATTRIBUTE_NS_URI,"xmlns:x",NS);
                changed=true;
            }
            if(!changed)continue;
            var output=new ByteArrayOutputStream();
            var transformers=TransformerFactory.newDefaultInstance();
            transformers.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING,true);
            transformers.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD,"");
            transformers.setAttribute(XMLConstants.ACCESS_EXTERNAL_STYLESHEET,"");
            transformers.newTransformer().transform(new DOMSource(xml),new StreamResult(output));
            part.clear();
            try(var target=part.getOutputStream()){output.writeTo(target);}
        }
    }
}
