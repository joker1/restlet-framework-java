/**
 * Copyright 2005-2009 Noelios Technologies.
 * 
 * The contents of this file are subject to the terms of one of the following
 * open source licenses: LGPL 3.0 or LGPL 2.1 or CDDL 1.0 or EPL 1.0 (the
 * "Licenses"). You can select the license that you prefer but you may not use
 * this file except in compliance with one of these Licenses.
 * 
 * You can obtain a copy of the LGPL 3.0 license at
 * http://www.opensource.org/licenses/lgpl-3.0.html
 * 
 * You can obtain a copy of the LGPL 2.1 license at
 * http://www.opensource.org/licenses/lgpl-2.1.php
 * 
 * You can obtain a copy of the CDDL 1.0 license at
 * http://www.opensource.org/licenses/cddl1.php
 * 
 * You can obtain a copy of the EPL 1.0 license at
 * http://www.opensource.org/licenses/eclipse-1.0.php
 * 
 * See the Licenses for the specific language governing permissions and
 * limitations under the Licenses.
 * 
 * Alternatively, you can obtain a royalty free commercial license with less
 * limitations, transferable or non-transferable, directly at
 * http://www.noelios.com/products/restlet-engine
 * 
 * Restlet is a registered trademark of Noelios Technologies.
 */

package org.restlet.example.book.restlet.ch10;

import java.util.ArrayList;
import java.util.List;

import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;

import org.restlet.data.MediaType;
import org.restlet.ext.xml.DomRepresentation;
import org.restlet.representation.Representation;
import org.restlet.representation.Variant;
import org.restlet.resource.ResourceException;
import org.restlet.resource.ServerResource;
import org.w3c.dom.Document;
import org.w3c.dom.Element;

/**
 *
 */
public class DomResource extends ServerResource {

    @Override
    public Representation get(Variant variant) throws ResourceException {
        Representation rep = null;

        try {
            final Document d = DocumentBuilderFactory.newInstance()
                    .newDocumentBuilder().newDocument();
            final Element r = d.createElement("mail");
            d.appendChild(r);
            final Element subject = d.createElement("subject");
            subject.appendChild(d
                    .createTextNode("This is the topic of the mail."));
            r.appendChild(subject);
            final Element from = d.createElement("from");
            from.appendChild(d.createTextNode("Me"));
            r.appendChild(from);
            d.normalizeDocument();
            rep = new DomRepresentation(MediaType.TEXT_XML, d);
        } catch (ParserConfigurationException e) {
            e.printStackTrace();
        }

        return rep;
    }

    @Override
    public List<Variant> getVariants() {
        List<Variant> variants = new ArrayList<Variant>();
        variants.add(new Variant(MediaType.TEXT_XML));
        return variants;
    }

}
