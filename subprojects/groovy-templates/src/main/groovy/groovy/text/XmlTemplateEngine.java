/*
 *  Licensed to the Apache Software Foundation (ASF) under one
 *  or more contributor license agreements.  See the NOTICE file
 *  distributed with this work for additional information
 *  regarding copyright ownership.  The ASF licenses this file
 *  to you under the Apache License, Version 2.0 (the
 *  "License"); you may not use this file except in compliance
 *  with the License.  You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing,
 *  software distributed under the License is distributed on an
 *  "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 *  KIND, either express or implied.  See the License for the
 *  specific language governing permissions and limitations
 *  under the License.
 */
package groovy.text;

import groovy.lang.Binding;
import groovy.lang.Closure;
import groovy.lang.GroovyRuntimeException;
import groovy.lang.GroovyShell;
import groovy.lang.Script;
import groovy.lang.Writable;
import groovy.namespace.QName;
import groovy.util.IndentPrinter;
import groovy.util.Node;
import groovy.xml.XmlNodePrinter;
import groovy.xml.XmlParser;
import org.apache.groovy.io.StringBuilderWriter;
import org.codehaus.groovy.control.CompilationFailedException;
import org.codehaus.groovy.runtime.FormatHelper;
import org.codehaus.groovy.runtime.InvokerHelper;
import org.xml.sax.SAXException;

import javax.xml.parsers.ParserConfigurationException;
import java.io.IOException;
import java.io.PrintWriter;
import java.io.Reader;
import java.io.Writer;
import java.lang.ref.WeakReference;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Template engine for use in templating scenarios where both the template
 * source and the expected output are intended to be XML.
 * <p>
 * Templates may use the normal '${expression}' and '$variable' notations
 * to insert an arbitrary expression into the template.
 * In addition, support is also provided for special tags:
 * &lt;gsp:scriptlet&gt; (for inserting code fragments) and
 * &lt;gsp:expression&gt; (for code fragments which produce output).
 * <p>
 * Comments and processing instructions
 * will be removed as part of processing and special XML characters such as
 * &lt;, &gt;, &quot; and &apos; will be escaped using the respective XML notation.
 * The output will also be indented using standard XML pretty printing.
 * <p>
 * The xmlns namespace definition for <code>gsp:</code> tags will be removed
 * but other namespace definitions will be preserved (but may change to an
 * equivalent position within the XML tree).
 * <p>
 * Normally, the template source will be in a file but here is a simple
 * example providing the XML template as a string:
 * <pre>
 * def binding = [firstname:"Jochen", lastname:"Theodorou",
 *                nickname:"blackdrag", salutation:"Dear"]
 * def engine = new groovy.text.XmlTemplateEngine()
 * def text = '''\
 * &lt;?xml version="1.0" encoding="UTF-8"?&gt;
 * &lt;document xmlns:gsp='http://groovy.codehaus.org/2005/gsp' xmlns:foo='baz' type='letter'&gt;
 *   &lt;gsp:scriptlet&gt;def greeting = "${salutation}est"&lt;/gsp:scriptlet&gt;
 *   &lt;gsp:expression&gt;greeting&lt;/gsp:expression&gt;
 *   &lt;foo:to&gt;$firstname "$nickname" $lastname&lt;/foo:to&gt;
 *   How are you today?
 * &lt;/document&gt;
 * '''
 * def template = engine.createTemplate(text).make(binding)
 * println template.toString()
 * </pre>
 * This example will produce this output:
 * <pre>
 * &lt;document type='letter'&gt;
 * Dearest
 * &lt;foo:to xmlns:foo='baz'&gt;
 *   Jochen &amp;quot;blackdrag&amp;quot; Theodorou
 * &lt;/foo:to&gt;
 * How are you today?
 * &lt;/document&gt;
 * </pre>
 * The XML template engine can also be used as the engine for {@code groovy.servlet.TemplateServlet} by placing the
 * following in your web.xml file (plus a corresponding servlet-mapping element):
 * <pre>
 * &lt;servlet&gt;
 *   &lt;servlet-name&gt;XmlTemplate&lt;/servlet-name&gt;
 *   &lt;servlet-class&gt;groovy.servlet.TemplateServlet&lt;/servlet-class&gt;
 *   &lt;init-param&gt;
 *     &lt;param-name&gt;template.engine&lt;/param-name&gt;
 *     &lt;param-value&gt;groovy.text.XmlTemplateEngine&lt;/param-value&gt;
 *   &lt;/init-param&gt;
 * &lt;/servlet&gt;
 * </pre>
 */
public class XmlTemplateEngine extends TemplateEngine {

    private static AtomicInteger counter = new AtomicInteger(0);

    private Closure configurePrinter = null;

    /**
     * Closure that can be used to configure the printer.
     * The printer is passed as a parameter to the closure.
     * <pre>
     * new XmlTemplateEngine(configurePrinter: { it.preserveWhitespace = true })
     * </pre>
     */
    public void setConfigurePrinter(Closure configurePrinter) {
        this.configurePrinter = configurePrinter;
    }

    private static class GspPrinter extends XmlNodePrinter {

        GspPrinter(PrintWriter out, String indent) {
            this(new IndentPrinter(out, indent));
        }

        GspPrinter(IndentPrinter out) {
            super(out, "\\\"");
            setQuote("'");
        }

        protected void printGroovyTag(String tag, String text) {
            if ("scriptlet".equals(tag)) {
                out.print(text);
                out.print("\n");
                return;
            }
            if ("expression".equals(tag)) {
                printLineBegin();
                out.print("${");
                out.print(text);
                out.print("}");
                printLineEnd();
                return;
            }
            throw new RuntimeException("Unsupported 'gsp:' tag named \"" + tag + "\".");
        }

        @Override
        protected void printName(Node node, NamespaceContext ctx, boolean begin, boolean preserve) {
            if (node == null || node.name() == null) {
                super.printName(node, ctx, begin, preserve);
            }
            printLineBegin();
            out.print("<");
            if (!begin) {
                out.print("/");
            }
            out.print(getName(node));
            if (ctx != null) {
                printNamespace(node, ctx);
            }
            if (begin) {
                printNameAttributes(node.attributes(), ctx);
            }
            out.print(">");
            printLineEnd();
        }

        @Override
        protected void printSimpleItem(Object value) {
            this.printLineBegin();
            out.print(escapeSpecialChars(FormatHelper.toString(value)));
            printLineEnd();
        }

        private String escapeSpecialChars(String s) {
            StringBuilder sb = new StringBuilder();
            boolean inGString = false;
            for (int i = 0; i < s.length(); i++) {
                final char c = s.charAt(i);
                switch (c) {
                    case '$':
                        sb.append("$");
                        if (i < s.length() - 1 && s.charAt(i + 1) == '{') inGString = true;
                        break;
                    case '<':
                        append(sb, c, "&lt;", inGString);
                        break;
                    case '>':
                        append(sb, c, "&gt;", inGString);
                        break;
                    case '"':
                        append(sb, c, "&quot;", inGString);
                        break;
                    case '\'':
                        append(sb, c, "&apos;", inGString);
                        break;
                    case '}':
                        sb.append(c);
                        inGString = false;
                        break;
                    default:
                        sb.append(c);
                }
            }
            return sb.toString();
        }

        private void append(StringBuilder sb, char plainChar, String xmlString, boolean inGString) {
            if (inGString) {
                sb.append(plainChar);
            } else {
                sb.append(xmlString);
            }
        }

        @Override
        protected void printLineBegin() {
            out.print("out.print(\"\"\"");
            if (!isPreserveWhitespace()) out.printIndent();
        }

        @Override
        protected void printLineEnd(String comment) {
            if (!isPreserveWhitespace()) out.print("\\n");
            out.print("\"\"\");");
            if (comment != null) {
                out.print(" // ");
                out.print(comment);
            }
            out.print("\n");
        }

        @Override
        protected boolean printSpecialNode(Node node) {
            Object name = node.name();
            if (name instanceof QName) {
                QName qn = (QName) name;
                // check uri and for legacy cases just check prefix name (not recommended)
                if ("http://groovy.codehaus.org/2005/gsp".equals(qn.getNamespaceURI()) || "gsp".equals(qn.getPrefix())) {
                    String s = qn.getLocalPart();
                    if (s.length() == 0) {
                        throw new RuntimeException("No local part after 'gsp:' given in node " + node);
                    }
                    printGroovyTag(s, node.text());
                    return true;
                }
            }
            return false;
        }
    }

    private static class XmlTemplate implements Template {

        private final Script script;

        XmlTemplate(Script script) {
            this.script = script;
        }

        @Override
        public Writable make() {
            return make(new HashMap());
        }

        @Override
        public Writable make(Map map) {
            if (map == null) {
                throw new IllegalArgumentException("map must not be null");
            }
            return new XmlWritable(script, new Binding(map));
        }
    }

    private static class XmlWritable implements Writable {

        private final Binding binding;
        private final Script script;
        private WeakReference result;

        XmlWritable(Script script, Binding binding) {
            this.script = script;
            this.binding = binding;
            this.result = new WeakReference<>(null);
        }

        @Override
        public Writer writeTo(Writer out) {
            Script scriptObject = InvokerHelper.createScript(script.getClass(), binding);
            PrintWriter pw = new PrintWriter(out);
            scriptObject.setProperty("out", pw);
            scriptObject.run();
            pw.flush();
            return out;
        }

        @Override
        public String toString() {
            Object o = result.get();
            if (o != null) {
                return o.toString();
            }
            String string = writeTo(new StringBuilderWriter(1024)).toString();
            result = new WeakReference<>(string);
            return string;
        }
    }

    public static final String DEFAULT_INDENTATION = "  ";

    private final GroovyShell groovyShell;
    private final XmlParser xmlParser;
    private String indentation;

    public XmlTemplateEngine() throws SAXException, ParserConfigurationException {
        this(DEFAULT_INDENTATION, false);
    }

    public XmlTemplateEngine(String indentation, boolean validating) throws SAXException, ParserConfigurationException {
        this(new XmlParser(validating, true), new GroovyShell());
        this.xmlParser.setTrimWhitespace(true);
        setIndentation(indentation);
    }

    public XmlTemplateEngine(XmlParser xmlParser, ClassLoader parentLoader) {
        this(xmlParser, new GroovyShell(parentLoader));
    }

    public XmlTemplateEngine(XmlParser xmlParser, GroovyShell groovyShell) {
        this.groovyShell = groovyShell;
        this.xmlParser = xmlParser;
        setIndentation(DEFAULT_INDENTATION);
    }

    @Override
    public Template createTemplate(Reader reader) throws CompilationFailedException, ClassNotFoundException, IOException {
        Node root ;
        try {
            root = xmlParser.parse(reader);
        } catch (SAXException e) {
            throw new RuntimeException("Parsing XML source failed.", e);
        }

        if (root == null) {
            throw new IOException("Parsing XML source failed: root node is null.");
        }

        StringBuilderWriter writer = new StringBuilderWriter(1024);
        writer.write("/* Generated by XmlTemplateEngine */\n");
        GspPrinter printer = new GspPrinter(new PrintWriter(writer), indentation);
        if (configurePrinter != null) {
            configurePrinter.call(printer);
        }
        printer.print(root);

        Script script;
        try {
            script = groovyShell.parse(writer.toString(), "XmlTemplateScript" + counter.incrementAndGet() + ".groovy");
        } catch (Exception e) {
            throw new GroovyRuntimeException("Failed to parse template script (your template may contain an error or be trying to use expressions not currently supported): " + e.getMessage());
        }
        return new XmlTemplate(script);
    }

    public String getIndentation() {
        return indentation;
    }

    public void setIndentation(String indentation) {
        if (indentation == null) {
            indentation = DEFAULT_INDENTATION;
        }
        this.indentation = indentation;
    }

    @Override
    public String toString() {
        return "XmlTemplateEngine";
    }

}
