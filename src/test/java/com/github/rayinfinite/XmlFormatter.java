package com.github.rayinfinite;

import org.dom4j.Attribute;
import org.dom4j.Document;
import org.dom4j.Element;
import org.dom4j.io.OutputFormat;
import org.dom4j.io.SAXReader;
import org.dom4j.io.XMLWriter;

import java.io.StringReader;
import java.io.StringWriter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

public class XmlFormatter {

    public static String format(String xmlString) throws Exception {
        SAXReader reader = new SAXReader();
        Document document = reader.read(new StringReader(xmlString));

        // 先对所有元素的属性进行排序
        Element root = document.getRootElement();
        if (root != null) {
            reorderAttributesRecursively(root);
        }

        // 配置输出格式
        OutputFormat format = OutputFormat.createPrettyPrint();
        format.setIndent(true);
        format.setIndent("  "); // 缩进2个空格
        format.setNewlines(true);

        // 输出
        StringWriter stringWriter = new StringWriter();
        XMLWriter writer = new XMLWriter(stringWriter, format);
        writer.write(document);
        writer.close();

        return stringWriter.toString();
    }

    @SuppressWarnings("unchecked")
    private static void reorderAttributesRecursively(Element element) {
        // 收集当前元素的属性副本
        List<Attribute> attrs = new ArrayList<>(element.attributes());
        if (attrs.size() > 1) {
            // 按属性名字母序排序（不区分大小写的简单实现）
            Collections.sort(attrs, new Comparator<Attribute>() {
                @Override
                public int compare(Attribute a1, Attribute a2) {
                    return a1.getName().compareTo(a2.getName());
                }
            });

            // 移除原有属性并按排序后顺序重新添加
            for (Attribute a : new ArrayList<>(element.attributes())) {
                element.remove(a);
            }
            for (Attribute a : attrs) {
                // 使用 addAttribute(name, value) 重新添加属性
                element.addAttribute(a.getName(), a.getValue());
            }
        }

        // 递归处理子元素
        for (Element child : (List<Element>) element.elements()) {
            reorderAttributesRecursively(child);
        }
    }
}
