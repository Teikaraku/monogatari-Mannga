package app.monogatari.android.local

import android.util.Xml
import org.w3c.dom.Document
import org.xmlpull.v1.XmlPullParser
import org.xml.sax.SAXException
import java.io.ByteArrayInputStream
import javax.xml.parsers.DocumentBuilderFactory

/** Portable Android XML validation; security does not depend on optional JAXP feature URIs. */
object ArchiveXml {
    fun parse(bytes:ByteArray):Document {
        val tokens=Xml.newPullParser()
        tokens.setInput(ByteArrayInputStream(bytes),null)
        var token=tokens.eventType
        while(token!=XmlPullParser.END_DOCUMENT) {
            require(token!=XmlPullParser.DOCDECL) { "Archive XML must not contain a DTD" }
            token=tokens.nextToken()
        }
        val builder=DocumentBuilderFactory.newInstance().apply {
            isNamespaceAware=true
            isExpandEntityReferences=false
        }.newDocumentBuilder()
        builder.setEntityResolver { _,_ ->throw SAXException("External XML entities are not allowed") }
        return builder.parse(ByteArrayInputStream(bytes))
    }
}
