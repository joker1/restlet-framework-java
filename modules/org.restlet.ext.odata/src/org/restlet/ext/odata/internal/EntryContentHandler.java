/**
 * Copyright 2005-2010 Noelios Technologies.
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

package org.restlet.ext.odata.internal;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.Logger;

import org.restlet.Context;
import org.restlet.data.Language;
import org.restlet.data.MediaType;
import org.restlet.data.Reference;
import org.restlet.ext.atom.Content;
import org.restlet.ext.atom.Entry;
import org.restlet.ext.atom.EntryReader;
import org.restlet.ext.atom.Feed;
import org.restlet.ext.atom.Link;
import org.restlet.ext.atom.Person;
import org.restlet.ext.atom.Relation;
import org.restlet.ext.odata.Service;
import org.restlet.ext.odata.internal.edm.AssociationEnd;
import org.restlet.ext.odata.internal.edm.EntityType;
import org.restlet.ext.odata.internal.edm.Mapping;
import org.restlet.ext.odata.internal.edm.Metadata;
import org.restlet.ext.odata.internal.edm.Property;
import org.restlet.ext.odata.internal.reflect.ReflectUtils;
import org.xml.sax.Attributes;
import org.xml.sax.SAXException;

/**
 * Content handler for Atom Feed that takes care of OData specific needs, such
 * as parsing XML content from other namespaces than Atom. It generates an
 * entity based on the values discovered in the entry.
 * 
 * @author Thierry Boileau
 * @param <T>
 *            The type of the parsed entity.
 */
public class EntryContentHandler<T> extends EntryReader {

    private enum State {
        ASSOCIATION, CONTENT, ENTRY, PROPERTIES, PROPERTY
    }

    /** The currently parsed association. */
    private AssociationEnd association;

    /** The currently parsed inline content. */
    private Content inlineContent;

    /** The currently parsed inline entry. */
    private Entry inlineEntry;

    /** The currently parsed inline feed. */
    private Feed inlineFeed;

    /** The currently parsed inline link. */
    private Link inlineLink;

    /** The path of the current XML element relatively to the Entry. */
    List<String> eltPath;

    /** The entity targeted by this entry. */
    private T entity;

    /** The class of the entity targeted by this entry. */
    private Class<?> entityClass;

    /** The OData type of the parsed entity. */
    private EntityType entityType;

    /** Used to parsed Atom link elements that contains entries. */
    EntryContentHandler<T> inlineEntryHandler;

    /** Used to parsed Atom link elements that contains feeds. */
    FeedContentHandler<T> inlineFeedHandler;

    /** Internal logger. */
    private Logger logger;

    /** The currently parsed OData mapping. */
    private Mapping mapping;

    /** The metadata of the WCF service. */
    private Metadata metadata;

    /** Must the current property be set to null? */
    private boolean parsePropertyNull;

    /** Used to handle complex types. */
    private List<String> propertyPath;

    /** Gleans text content. */
    StringBuilder sb = null;

    /** Heap of states. */
    List<State> states;

    /**
     * Constructor.
     * 
     * @param entityClass
     *            The class of the parsed entities.
     * @param entityType
     *            The entity type of the parsed entities.
     * @param metadata
     *            The metadata of the remote OData service.
     * @param logger
     *            The logger.
     */
    public EntryContentHandler(Class<?> entityClass, EntityType entityType,
            Metadata metadata, Logger logger) {
        this.entityClass = entityClass;
        this.entityType = entityType;
        this.metadata = metadata;
        this.logger = logger;
    }

    /**
     * Constructor.
     * 
     * @param entityClass
     *            The class of the parsed entities.
     * @param metadata
     *            The metadata of the remote OData service.
     * @param logger
     *            The logger.
     */
    public EntryContentHandler(Class<?> entityClass, Metadata metadata,
            Logger logger) {
        super();
        this.entityClass = entityClass;
        this.entityType = metadata.getEntityType(entityClass);
        this.metadata = metadata;
        this.logger = logger;
    }

    @Override
    public void characters(char[] ch, int start, int length)
            throws SAXException {
        if (State.ASSOCIATION == getState()) {
            // Delegates to the inline content handler
            if (association.isToMany()) {
                inlineFeedHandler.characters(ch, start, length);
            } else {
                inlineEntryHandler.characters(ch, start, length);
            }
        } else if (State.PROPERTY == getState() || mapping != null) {
            sb.append(ch, start, length);
        }
    }

    @Override
    public void endContent(Content content) {
        if (State.ASSOCIATION == getState()) {
            // Delegates to the inline content handler
            if (association.isToMany()) {
                inlineFeedHandler.endContent(content);
            } else {
                inlineEntryHandler.endContent(content);
            }
        } else {
            popState();
        }
    }

    @Override
    public void endElement(String uri, String localName, String qName)
            throws SAXException {
        if (State.ASSOCIATION == getState()) {
            // Delegates to the inline content handler
            if (uri.equalsIgnoreCase(Feed.ATOM_NAMESPACE)) {
                if (localName.equals("feed")) {
                    inlineFeedHandler.endFeed(inlineFeed);
                } else if (localName.equals("link")) {
                    if (association.isToMany()) {
                        inlineFeedHandler.endLink(inlineLink);
                    } else {
                        inlineEntryHandler.endLink(inlineLink);
                    }
                } else if (localName.equalsIgnoreCase("entry")) {
                    if (association.isToMany()) {
                        inlineFeedHandler.endEntry(inlineEntry);
                    } else {
                        inlineEntryHandler.endEntry(inlineEntry);
                    }
                } else if (localName.equalsIgnoreCase("content")) {
                    if (association.isToMany()) {
                        inlineFeedHandler.endContent(inlineContent);
                    } else {
                        inlineEntryHandler.endContent(inlineContent);
                    }
                }
            }

            if (association.isToMany()) {
                inlineFeedHandler.endElement(uri, localName, qName);
            } else {
                inlineEntryHandler.endElement(uri, localName, qName);
            }
        } else if (State.PROPERTY == getState()) {
            if (parsePropertyNull) {
                popState();
                parsePropertyNull = false;
            } else {
                Object obj = entity;
                if (propertyPath.size() > 1) {
                    // Complex property.
                    for (int i = 0; i < propertyPath.size() - 1; i++) {
                        try {
                            Object o = ReflectUtils.invokeGetter(obj,
                                    propertyPath.get(i));
                            if (o == null) {
                                // Try to instantiate it
                                Field[] fields = obj.getClass()
                                        .getDeclaredFields();
                                for (Field field : fields) {
                                    if (field.getName().equalsIgnoreCase(
                                            propertyPath.get(i))) {
                                        o = field.getType().newInstance();
                                        break;
                                    }
                                }
                            }
                            ReflectUtils.invokeSetter(obj, propertyPath.get(i),
                                    o);
                            obj = o;
                        } catch (Exception e) {
                            obj = null;
                        }
                    }
                }
                Property property = metadata.getProperty(obj, localName);
                try {
                    ReflectUtils.setProperty(obj, property, sb.toString());
                } catch (Exception e) {
                    getLogger().warning(
                            "Cannot set " + localName + " property on " + obj
                                    + " with value " + sb.toString());
                }
                popState();
                if (!propertyPath.isEmpty()) {
                    propertyPath.remove(propertyPath.size() - 1);
                }
            }
        } else if (State.PROPERTIES == getState()) {
            popState();
        } else if (State.CONTENT == getState()) {
            popState();
        } else if (mapping != null) {
            // A mapping has been discovered
            if (sb != null) {
                try {
                    ReflectUtils.invokeSetter(entity,
                            mapping.getPropertyPath(), sb.toString());
                } catch (Exception e) {
                    getLogger().warning(
                            "Cannot set the mapped property "
                                    + mapping.getPropertyPath() + " on "
                                    + entity + " with value " + sb.toString());

                }
            }
            mapping = null;
        } else if (State.ENTRY == getState()) {
            if (!eltPath.isEmpty()) {
                eltPath.remove(eltPath.size() - 1);
            }
        }
    }

    @Override
    public void endEntry(Entry entry) {
        this.states = new ArrayList<State>();

        // Handle Atom mapped values.
        for (Mapping m : metadata.getMappings()) {
            if (entityType != null && entityType.equals(m.getType())
                    && m.getNsUri() == null && m.getNsPrefix() == null) {
                // mapping atom
                Person author = (entry.getAuthors().isEmpty()) ? null : entry
                        .getAuthors().get(0);
                Person contributor = (entry.getContributors().isEmpty()) ? null
                        : entry.getContributors().get(0);
                Object value = null;
                if ("SyndicationAuthorEmail".equals(m.getValuePath())) {
                    value = (author != null) ? author.getEmail() : null;
                } else if ("SyndicationAuthorName".equals(m.getValuePath())) {
                    value = (author != null) ? author.getName() : null;
                } else if ("SyndicationAuthorUri".equals(m.getValuePath())) {
                    value = (author != null) ? author.getUri().toString()
                            : null;
                } else if ("SyndicationContributorEmail".equals(m
                        .getValuePath())) {
                    value = (contributor != null) ? contributor.getEmail()
                            : null;
                } else if ("SyndicationContributorName"
                        .equals(m.getValuePath())) {
                    value = (contributor != null) ? contributor.getName()
                            : null;
                } else if ("SyndicationContributorUri".equals(m.getValuePath())) {
                    value = (contributor != null) ? contributor.getUri()
                            .toString() : null;
                } else if ("SyndicationPublished".equals(m.getValuePath())) {
                    value = entry.getPublished();
                } else if ("SyndicationRights".equals(m.getValuePath())) {
                    value = (entry.getRights() != null) ? entry.getRights()
                            .getContent() : null;
                } else if ("SyndicationSummary".equals(m.getValuePath())) {
                    value = entry.getSummary();
                } else if ("SyndicationTitle".equals(m.getValuePath())) {
                    value = (entry.getTitle() != null) ? entry.getTitle()
                            .getContent() : null;
                } else if ("SyndicationUpdated".equals(m.getValuePath())) {
                    value = entry.getUpdated();
                }

                try {
                    if (value != null) {
                        ReflectUtils.invokeSetter(entity, m.getPropertyPath(),
                                value);
                    }
                } catch (Exception e) {
                    getLogger().warning(
                            "Cannot set " + m.getPropertyPath()
                                    + " property on " + entity + " with value "
                                    + value);
                }
            }
        }

        // If the entity is a blob, get the edit reference
        if (entityType != null && entityType.isBlob()
                && entityType.getBlobValueEditRefProperty() != null) {
            // Look for en entry with a "edit-media" relation value.
            Link link = entry.getLink(Relation.EDIT_MEDIA);
            String pty = entityType.getBlobValueEditRefProperty().getName();
            if (link != null) {
                try {
                    ReflectUtils.invokeSetter(entity, pty, link.getHref());
                } catch (Exception e) {
                    getLogger().warning(
                            "Cannot set the property " + pty + " on " + entity
                                    + " with value " + link.getHref());
                }
            }
        }
    }

    @Override
    public void endLink(Link link) {
        if (State.ASSOCIATION == getState()) {
            String propertyName = ReflectUtils.normalize(link.getTitle());
            if (association.isToMany()) {
                inlineFeedHandler.endLink(link);
                try {
                    ReflectUtils.setProperty(entity, propertyName, association
                            .isToMany(), inlineFeedHandler.getEntities()
                            .iterator(), ReflectUtils.getSimpleClass(entity,
                            propertyName));
                } catch (Exception e) {
                    getLogger().warning(
                            "Cannot set " + propertyName + " property on "
                                    + entity + " from link");
                }
                inlineFeedHandler = null;
            } else {
                inlineEntryHandler.endLink(link);
                try {
                    ReflectUtils.invokeSetter(entity, propertyName,
                            inlineEntryHandler.getEntity());
                } catch (Exception e) {
                    getLogger().warning(
                            "Cannot set " + propertyName + " property on "
                                    + entity + " from link");
                }
                inlineEntryHandler = null;
            }

            // This works if the inline entries does not contain links as
            // well...
            popState();
            association = null;
        }
    }

    public T getEntity() {
        return entity;
    }

    /**
     * Returns the current logger.
     * 
     * @return The current logger.
     */
    private Logger getLogger() {
        if (logger == null) {
            logger = Context.getCurrentLogger();
        }
        return logger;
    }

    /**
     * Returns a media type from an Atom type attribute.
     * 
     * @param type
     *            The Atom type attribute.
     * @return The media type.
     */
    private MediaType getMediaType(String type) {
        MediaType result = null;

        if (type == null) {
            // No type defined
        } else if (type.equals("text")) {
            result = MediaType.TEXT_PLAIN;
        } else if (type.equals("html")) {
            result = MediaType.TEXT_HTML;
        } else if (type.equals("xhtml")) {
            result = MediaType.APPLICATION_XHTML;
        } else {
            result = new MediaType(type);
        }

        return result;
    }

    /**
     * Returns the state at the top of the heap.
     * 
     * @return The state at the top of the heap.
     */
    private State getState() {
        State result = null;
        if (this.states != null) {
            int size = this.states.size();
            if (size > 0) {
                result = this.states.get(size - 1);
            }
        }
        return result;
    }

    /**
     * Returns the state at the top of the heap and removes it from the heap.
     * 
     * @return The state at the top of the heap.
     */
    private State popState() {
        State result = null;
        int size = this.states.size();
        if (size > 0) {
            result = this.states.remove(size - 1);
        }

        return result;
    }

    /**
     * Adds a new state at the top of the heap.
     * 
     * @param state
     *            The state to add.
     */
    private void pushState(State state) {
        this.states.add(state);
    }

    @Override
    public void startContent(Content content) {
        if (State.ENTRY == getState()) {
            pushState(State.CONTENT);
            if (entityType != null && entityType.isBlob()
                    && entityType.getBlobValueRefProperty() != null) {
                Reference ref = content.getExternalRef();
                if (ref != null) {
                    try {
                        ReflectUtils.invokeSetter(entity, entityType
                                .getBlobValueRefProperty().getName(), ref);
                    } catch (Exception e) {
                        getLogger().warning(
                                "Cannot set "
                                        + entityType.getBlobValueRefProperty()
                                                .getName() + " property on "
                                        + entity + " with value " + ref);
                    }
                }
            }

        }
    }

    @Override
    public void startElement(String uri, String localName, String qName,
            Attributes attrs) throws SAXException {
        if (State.ASSOCIATION == getState()) {
            // Delegates to the inline content handler
            if (uri.equalsIgnoreCase(Feed.ATOM_NAMESPACE)) {
                if (localName.equals("feed")) {
                    Feed feed = new Feed();
                    String attr = attrs.getValue("xml:base");
                    if (attr != null) {
                        feed.setBaseReference(new Reference(attr));
                    }
                    inlineFeed = feed;
                    this.inlineFeedHandler.startFeed(feed);
                } else if (localName.equals("link")) {
                    Link link = new Link();
                    link.setHref(new Reference(attrs.getValue("", "href")));
                    link.setRel(Relation.valueOf(attrs.getValue("", "rel")));
                    String type = attrs.getValue("", "type");
                    if (type != null && type.length() > 0) {
                        link.setType(new MediaType(type));
                    }

                    link.setHrefLang(new Language(attrs
                            .getValue("", "hreflang")));
                    link.setTitle(attrs.getValue("", "title"));
                    String attr = attrs.getValue("", "length");
                    link.setLength((attr == null) ? -1L : Long.parseLong(attr));

                    inlineLink = link;
                    if (association.isToMany()) {
                        inlineFeedHandler.startLink(link);
                    } else {
                        inlineEntryHandler.startLink(link);
                    }
                } else if (localName.equalsIgnoreCase("entry")) {
                    Entry entry = new Entry();
                    if (association.isToMany()) {
                        inlineFeedHandler.startEntry(entry);
                    } else {
                        inlineEntryHandler.startEntry(entry);
                    }
                    inlineEntry = entry;
                } else if (localName.equalsIgnoreCase("content")) {
                    Content content = new Content();
                    MediaType type = getMediaType(attrs.getValue("", "type"));
                    String srcAttr = attrs.getValue("", "src");
                    if (srcAttr != null)
                        // Content available externally
                        content.setExternalRef(new Reference(srcAttr));
                    content.setExternalType(type);
                    if (association.isToMany()) {
                        inlineFeedHandler.startContent(content);
                    } else {
                        inlineEntryHandler.startContent(content);
                    }
                    inlineContent = content;
                }
            }

            if (association.isToMany()) {
                inlineFeedHandler.startElement(uri, localName, qName, attrs);
            } else {
                inlineEntryHandler.startElement(uri, localName, qName, attrs);
            }
        } else if (Service.WCF_DATASERVICES_METADATA_NAMESPACE.equals(uri)
                && "properties".equals(localName)) {
            pushState(State.PROPERTIES);
            propertyPath = new ArrayList<String>();
        } else if (State.PROPERTIES == getState()) {
            pushState(State.PROPERTY);

            if (Boolean.parseBoolean(attrs.getValue(
                    Service.WCF_DATASERVICES_METADATA_NAMESPACE, "null"))) {
                parsePropertyNull = true;
            } else {
                sb = new StringBuilder();
                propertyPath.add(localName);
            }
        } else if (State.PROPERTY == getState()) {
            propertyPath.add(localName);
        } else if (State.ENTRY == getState()) {
            if (localName.equalsIgnoreCase("link") && association != null) {
                pushState(State.ASSOCIATION);
            } else {
                // Could be mapped value
                eltPath.add(localName);
                StringBuilder sb = new StringBuilder();
                for (int i = 0; i < eltPath.size(); i++) {
                    if (i > 0) {
                        sb.append("/");
                    }
                    sb.append(eltPath.get(i));
                }
                String str = sb.toString();

                // Check if this path is mapped.
                for (Mapping m : metadata.getMappings()) {
                    if (entityType != null && entityType.equals(m.getType())
                            && m.getNsUri() != null && m.getNsUri().equals(uri)
                            && str.equals(m.getValueNodePath())) {
                        if (m.isAttributeValue()) {
                            String value = attrs.getValue(m
                                    .getValueAttributeName());
                            if (value != null) {
                                try {
                                    ReflectUtils.invokeSetter(entity, m
                                            .getPropertyPath(), value);
                                } catch (Exception e) {
                                    getLogger().warning(
                                            "Cannot set " + m.getPropertyPath()
                                                    + " property on " + entity
                                                    + " with value " + value);
                                }
                            }
                        } else {
                            this.sb = new StringBuilder();
                            mapping = m;
                        }
                        break;
                    }
                }
            }
        }
    }

    @SuppressWarnings("unchecked")
    @Override
    public void startEntry(Entry entry) {
        this.states = new ArrayList<State>();
        pushState(State.ENTRY);
        eltPath = new ArrayList<String>();
        // Instantiate the entity
        try {
            entity = (T) entityClass.newInstance();
        } catch (Exception e) {
            getLogger().warning(
                    "Error when instantiating  class " + entityClass);
        }
    }

    @Override
    public void startLink(Link link) {
        if (State.ASSOCIATION == getState()) {
            // Delegates to the inline content handler
            if (association.isToMany()) {
                inlineFeedHandler.startLink(link);
            } else {
                inlineEntryHandler.startLink(link);
            }
        } else {
            if (link.getTitle() != null && entityType != null) {
                String propertyName = ReflectUtils.normalize(link.getTitle());
                // Get the associated entity
                association = metadata.getAssociation(entityType, propertyName);
                if (association != null) {
                    if (association.isToMany()) {
                        inlineFeedHandler = new FeedContentHandler<T>(
                                ReflectUtils.getSimpleClass(entity,
                                        propertyName), metadata, getLogger());
                    } else {
                        inlineEntryHandler = new EntryContentHandler<T>(
                                ReflectUtils.getSimpleClass(entity,
                                        propertyName), metadata, getLogger());
                    }
                }
            }
        }
    }
}
