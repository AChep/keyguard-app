@file:Suppress("MemberVisibilityCanBePrivate")

package org.redundent.kotlin.xml

/**
 * Base type for all elements. This is what handles pretty much all the rendering and building.
 */
open class Node(val nodeName: String) : Element {
    private var parent: Node? = null
    private val _globalLevelProcessingInstructions = ArrayList<ProcessingInstructionElement>()
    private var doctype: Doctype? = null
    private val _namespaces: MutableSet<Namespace> = LinkedHashSet()
    private val _attributes: LinkedHashMap<String, Any?> = LinkedHashMap()
    private val _children = ArrayList<Element>()

    val namespaces: Collection<Namespace>
        get() = LinkedHashSet(_namespaces)

    /**
     * The default `xmlns` for the document. To add other namespaces, use the [namespace] method
     */
    var xmlns: String?
        get() = namespaces.firstOrNull(Namespace::isDefault)?.value
        set(value) {
            if (value != null) {
                addNamespace(Namespace(value))
            } else {
                _namespaces.removeAll { it.isDefault }
            }
        }

    /**
     * Whether to include the xml prolog, i.e. `<?xml version="1.0" encoding="UTS-8"?>`.
     *
     * NOTE: this only applies to the root element. It is ignored an all children.
     */
    var includeXmlProlog = false

    /**
     * Sets the encoding on the document. Setting this value will set [includeXmlProlog] to `true`.
     */
    var encoding: String = "UTF-8"
        set(value) {
            includeXmlProlog = true
            field = value
        }

    var version: XmlVersion = XmlVersion.V10
        set(value) {
            includeXmlProlog = true
            field = value
        }

    var standalone: Boolean? = null
        set(value) {
            includeXmlProlog = true
            field = value
        }

    /**
     * Any attributes that belong to this element. This will always return a copy of the attribute map.
     * @sample [set]
     */
    val attributes: Map<String, Any?>
        get() = _attributes.mapValues {
            val value = it.value
            if (value is Unsafe) value.value else it.value
        }

    val children: List<Element>
        get() = _children

    private fun getParentNamespaces(): Set<Namespace> {
        return generateSequence(parent, Node::parent)
            .flatMap { it.namespaces.asSequence() }
            .toSet()
    }

    private fun <T : Element> initTag(tag: T, init: (T.() -> Unit)?): T {
        if (init != null) {
            tag.init()
        }
        _children.add(tag)

        setParentIfNode(tag, this)

        return tag
    }

    /**
     * Allows for easy access of this node's attributes
     *
     * ```
     * val attr = element["key"]
     * ```
     */
    operator fun <T> get(attributeName: String): T? {
        val value = _attributes[attributeName]
        @Suppress("UNCHECKED_CAST")
        return (if (value is Unsafe) value.value else value) as T?
    }

    /**
     * Allows for easy access of adding/updating this node's attributes.
     * Setting the value of an attribute to `null` will remove the attribute.
     *
     * ```
     * element["key"] = "value"
     * ```
     */
    operator fun set(attributeName: String, value: Any?) {
        if (value == null) {
            removeAttribute(attributeName)
        } else {
            _attributes[attributeName] = value
        }
    }

    fun hasAttribute(attributeName: String): Boolean = _attributes.containsKey(attributeName)

    /**
     * Removes the specified attribute from the attributes map.
     */
    fun removeAttribute(attributeName: String) {
        _attributes.remove(attributeName)
    }

    override fun render(builder: Appendable, indent: String, printOptions: PrintOptions) {
        val lineEnding = getLineEnding(printOptions)
        builder.append("$indent<$nodeName${renderNamespaces()}${renderAttributes(printOptions)}")

        if (!isEmptyOrSingleEmptyTextElement()) {
            if (printOptions.pretty && printOptions.singleLineTextElements &&
                _children.size == 1 && _children[0] is TextElement
            ) {
                builder.append(">")
                (_children[0] as TextElement).renderSingleLine(builder, printOptions)
                builder.append("</$nodeName>$lineEnding")
            } else {
                builder.append(">$lineEnding")
                for (c in _children) {
                    c.render(builder, getIndent(printOptions, indent), printOptions)
                }

                builder.append("$indent</$nodeName>$lineEnding")
            }
        } else {
            builder.append("${getEmptyTagClosing(printOptions)}$lineEnding")
        }
    }

    private fun isEmptyOrSingleEmptyTextElement(): Boolean {
        if (_children.isEmpty()) {
            return true
        }

        if (_children.size == 1 && _children[0] is TextElement) {
            return (_children[0] as TextElement).text.isEmpty()
        }

        return false
    }

    private fun getEmptyTagClosing(printOptions: PrintOptions): String {
        return if (printOptions.useSelfClosingTags) {
            "/>"
        } else {
            "></$nodeName>"
        }
    }

    private fun renderNamespaces(): String {
        if (namespaces.isEmpty()) {
            return ""
        }

        val parentNamespaces = getParentNamespaces()
        val namespacesNeeded = namespaces
            .filterNot { parentNamespaces.contains(it) }

        if (namespacesNeeded.isEmpty()) {
            return ""
        }

        return namespacesNeeded.joinToString(" ", prefix = " ")
    }

    private fun renderAttributes(printOptions: PrintOptions): String {
        if (_attributes.isEmpty()) {
            return ""
        }

        return _attributes.entries.joinToString(" ", prefix = " ") {
            val value = it.value
            val text = if (value is Unsafe) {
                value.value?.toString()
            } else {
                escapeValue(
                    it.value,
                    printOptions.xmlVersion,
                    printOptions.useCharacterReference
                )
            }

            "${it.key}=\"$text\""
        }
    }

    private fun getIndent(printOptions: PrintOptions, indent: String): String {
        return if (!printOptions.pretty) {
            ""
        } else {
            "$indent${printOptions.indent}"
        }
    }

    /**
     * Get the XML representation of this object with `prettyFormat = true`.
     */
    override fun toString(): String = toString(prettyFormat = true)

    /**
     * Get the XML representation of this object.
     *
     * @param [prettyFormat] true to format the XML with newlines and tabs; otherwise no formatting
     */
    fun toString(prettyFormat: Boolean): String = toString(PrintOptions(pretty = prettyFormat))

    fun toString(printOptions: PrintOptions): String {
        return StringBuilder()
            .also { writeTo(it, printOptions) }
            .toString()
            .trim()
    }

    fun writeTo(appendable: Appendable, printOptions: PrintOptions = PrintOptions()) {
        val lineEnding = getLineEnding(printOptions)

        printOptions.xmlVersion = version

        if (includeXmlProlog) {
            appendable.append("<?xml version=\"${printOptions.xmlVersion.value}\" encoding=\"$encoding\"")

            standalone?.run {
                appendable.append(" standalone=\"${if (this) "yes" else "no"}\"")
            }

            appendable.append("?>$lineEnding")
        }

        doctype?.apply {
            render(appendable, "", printOptions)
        }

        if (_globalLevelProcessingInstructions.isNotEmpty()) {
            for (element in _globalLevelProcessingInstructions) {
                element.render(appendable, "", printOptions)
            }
        }

        render(appendable, "", printOptions)
    }

    fun unsafeText(text: String) {
        _children.add(TextElement(text, unsafe = true))
    }

    fun text(text: String) {
        _children.add(TextElement(text))
    }

    /**
     * Adds an XML comment to the document.
     * ```
     * comment("my comment")
     * ```
     *
     * @param text The text of the comment. This text will be rendered unescaped except for replace `--` with `&#45;&#45;`
     */
    fun comment(text: String) {
        _children.add(Comment(text))
    }

    /**
     * Adds a basic element with the specific name to the parent.
     * ```
     * element("url") {
     *     // ...
     * }
     * ```
     *
     * @param name The name of the element.
     * @param namespace Optional namespace object to use to build the name of the attribute.
     * @param init The block that defines the content of the element.
     */
    fun element(name: String, namespace: Namespace? = null, init: (Node.() -> Unit)? = null): Node {
        val node = Node(buildName(name, namespace))
        if (namespace != null) {
            node.addNamespace(namespace)
        }
        initTag(node, init)
        return node
    }

    /**
     * Adds a basic element with the specific name and value to the parent. This cannot be used for complex elements.
     * ```
     * element("url", "https://google.com")
     * ```
     *
     * @param name The name of the element.
     * @param value The inner text of the element
     * @param namespace Optional namespace object to use to build the name of the attribute.
     */
    fun element(name: String, value: String, namespace: Namespace? = null): Node {
        val node = Node(buildName(name, namespace))
        if (namespace != null) {
            node.addNamespace(namespace)
        }

        initTag(node) { text(value) }
        return node
    }

    private fun addNamespace(namespace: Namespace) {
        if (namespace.isDefault) {
            _namespaces.removeAll { it.isDefault }
        }
        _namespaces.add(namespace)
    }

    /**
     * Adds an attribute to the current element
     * ```
     * "url" {
     *    attribute("key", "value")
     * }
     * ```
     *
     * @param name The name of the attribute. This is currently no validation against the name.
     * @param value The attribute value.
     * @param namespace Optional namespace object to use to build the name of the attribute. Note this does NOT declare
     * the namespace. It simply uses it to build the attribute name.
     */
    fun attribute(name: String, value: Any, namespace: Namespace? = null) {
        if (namespace != null) {
            addNamespace(namespace)
        }
        _attributes[buildName(name, namespace)] = value
    }

    /**
     * Adds a set of attributes to the current element.
     * ```
     * "url" {
     *     attributes(
     *         "key" to "value",
     *         "id" to "1"
     *     )
     * }
     * ```
     * @param attrs Collection of the attributes to apply to this element.
     * @see attribute
     */
    fun attributes(vararg attrs: Pair<String, Any>) {
        attrs.forEach { attribute(it.first, it.second) }
    }

    /**
     * Adds a set of attributes to the current element.
     * ```
     * "url" {
     *     attributes(
     *         Attribute("key", "value", namespace),
     *         Attribute("id", "1", namespace)
     *     )
     * }
     * ```
     *
     * @param attrs Collection of the attributes to apply to this element.
     * @see attribute
     */
    fun attributes(vararg attrs: Attribute) {
        for (attr in attrs) {
            if (attr.namespace != null) {
                addNamespace(attr.namespace)
            }
            attribute(buildName(attr.name, attr.namespace), attr.value)
        }
    }

    /**
     * Adds a set of attributes to the current element.
     *
     * ```
     * "url" {
     *     attributes(
     *         "key" to "value",
     *         "id" to "1"
     *     )
     * }
     * ```
     *
     * @param namespace Optional namespace object to use to build the name of the attribute. Note this does NOT declare
     * the namespace. It simply uses it to build the attribute name(s).
     * @param attrs Collection of the attributes to apply to this element.
     * @see attribute
     */
    fun attributes(namespace: Namespace, vararg attrs: Pair<String, Any>) {
        attrs.forEach { attribute(it.first, it.second, namespace) }
    }

    /**
     * Adds the supplied text as a CDATA element
     *
     * @param text The inner text of the CDATA element.
     */
    fun cdata(text: String) {
        _children.add(CDATAElement(text))
    }

    /**
     * Adds the supplied text as a processing instruction element
     *
     * @param text The inner text of the processing instruction element.
     * @param attributes Optional set of attributes to apply to this processing instruction.
     */
    fun processingInstruction(text: String, vararg attributes: Pair<String, String>) {
        _children.add(ProcessingInstructionElement(text, linkedMapOf(*attributes)))
    }

    /**
     * Adds the supplied text as a processing instruction element to the root of the document.
     *
     * @param text The inner text of the processing instruction element.
     * @param attributes Optional set of attributes to apply to this processing instruction.
     */
    fun globalProcessingInstruction(text: String, vararg attributes: Pair<String, String>) {
        _globalLevelProcessingInstructions.add(
            ProcessingInstructionElement(
                text,
                linkedMapOf(*attributes)
            )
        )
    }

    /**
     * Add a DTD to the document.
     *
     * @param name The name of the DTD element. Not supplying this or passing <code>null</code> will default to [nodeName].
     * @param publicId The public declaration of the DTD.
     * @param systemId The system declaration of the DTD.
     */
    fun doctype(name: String? = null, publicId: String? = null, systemId: String? = null) {
        if (publicId != null && systemId == null) {
            throw IllegalStateException("systemId must be provided if publicId is provided")
        }

        doctype = Doctype(name ?: nodeName, publicId = publicId, systemId = systemId)
    }

    /**
     * Adds the specified namespace to the element.
     * ```
     * "url" {
     *     namespace("t", "http://someurl.org")
     * }
     * ```
     *
     * @param name The name of the namespace.
     * @param value The url or descriptor of the namespace.
     */
    fun namespace(name: String, value: String): Namespace {
        val ns = Namespace(name, value)
        namespace(ns)
        return ns
    }

    /**
     * Adds the specified namespace to the element.
     * ```
     * val ns = Namespace("t", "http://someurl.org")
     * "url" {
     *     namespace(ns)
     * }
     * ```
     *
     * @param namespace The namespace object to use for the element's namespace declaration.
     */
    fun namespace(namespace: Namespace) {
        addNamespace(namespace)
    }

    /**
     * Adds a element to the node.
     * @param element The element to append.
     */
    fun addElement(element: Element) {
        setParentIfNode(element, this)
        _children.add(element)
    }

    /**
     * Adds the provided elements to the node.
     * @param elements The elements to append.
     */
    fun addElements(vararg elements: Element) {
        elements.forEach { addElement(it) }
    }

    /**
     * Adds the provided elements to the node.
     * @param elements The elements to append.
     */
    fun <T : Element> addElements(elements: Iterable<T>) {
        elements.forEach { addElement(it) }
    }

    /**
     * Adds an element to the node after the specific element.
     * @param element The element to add
     * @param after The element to add [element] after
     *
     * @throws IllegalArgumentException If [after] can't be found
     */
    fun addElementAfter(element: Element, after: Element) {
        val index = findIndex(after)

        setParentIfNode(element, this)

        if (index + 1 == _children.size) {
            _children.add(element)
        } else {
            _children.add(index + 1, element)
        }
    }

    /**
     * Adds elements to the node after the specific element.
     * @param elements The elements to add
     * @param after The element to add [elements] after
     *
     * @throws IllegalArgumentException If [after] can't be found
     */
    fun <T : Element> addElementsAfter(elements: Iterable<T>, after: Element) {
        val index = findIndex(after) + 1

        if (index == _children.size) {
            addElements(elements)
        } else {
            val firstPart = _children.take(index)
            val lastPart = _children.drop(index)
            _children.clear()
            _children.addAll(firstPart)
            addElements(elements)
            _children.addAll(lastPart)
        }
    }

    /**
     * Adds elements to the node after the specific element.
     * @param after The element to add [elements] after
     * @param elements The elements to add
     *
     * @throws IllegalArgumentException If [after] can't be found
     */
    fun addElementsAfter(after: Element, vararg elements: Element) {
        addElementsAfter(listOf(*elements), after)
    }

    /**
     * Adds an element to the node before the specific element.
     * @param element The element to add
     * @param before The element to add [element] before
     *
     * @throws IllegalArgumentException If [before] can't be found
     */
    fun addElementBefore(element: Element, before: Element) {
        val index = findIndex(before)
        setParentIfNode(element, this)
        _children.add(index, element)
    }

    /**
     * Adds elements to the node before the specific element.
     * @param elements The elements to add
     * @param before The element to add [elements] before
     *
     * @throws IllegalArgumentException If [before] can't be found
     */
    fun <T : Element> addElementsBefore(elements: Iterable<T>, before: Element) {
        val index = findIndex(before)
        val firstPart = _children.take(index)
        val lastPart = _children.drop(index)
        _children.clear()
        _children.addAll(firstPart)
        addElements(elements)
        _children.addAll(lastPart)
    }

    /**
     * Adds elements to the node before the specific element.
     * @param before The element to add [elements] before
     * @param elements The elements to add
     *
     * @throws IllegalArgumentException If [before] can't be found
     */
    fun addElementsBefore(before: Element, vararg elements: Element) {
        addElementsBefore(listOf(*elements), before)
    }

    /**
     * Removes an element from the node.
     * @param element The element to remove
     *
     * @throws IllegalArgumentException If [element] can't be found
     */
    fun removeElement(element: Element) {
        val index = findIndex(element)
        removeChildAt(index)
    }

    /**
     * Removes the elements from the node.
     * @param elements The elements to remove
     *
     * @throws IllegalArgumentException If any [elements] can't be found
     */
    fun removeElements(vararg elements: Element) {
        removeElements(listOf(*elements))
    }

    /**
     * Removes the elements from the node.
     * @param elements The elements to remove
     *
     * @throws IllegalArgumentException If any [elements] can't be found
     */
    fun <T : Element> removeElements(elements: Iterable<T>) {
        elements
            .map { findIndex(it) }
            .sortedDescending()
            .forEach { removeChildAt(it) }
    }

    private fun removeChildAt(index: Int) {
        val child = _children.removeAt(index)
        setParentIfNode(child, null)
    }

    /**
     * Replaces an element with a different element.
     * @param existing The existing element to replace
     * @param newElement The element to replace [existing] with
     *
     * @throws IllegalArgumentException If [existing] can't be found
     */
    fun replaceElement(existing: Element, newElement: Element) {
        val index = findIndex(existing)

        setParentIfNode(newElement, this)
        setParentIfNode(existing, null)

        _children[index] = newElement
    }

    /**
     * Returns a list containing only elements whose nodeName matches [name].
     */
    fun filter(name: String): List<Node> = filter { it.nodeName == name }

    /**
     * Returns a list containing only elements matching the given [predicate].
     */
    fun filter(predicate: (Node) -> Boolean): List<Node> = filterChildrenToNodes().filter(predicate)

    /**
     * Returns the first element whose nodeName matches [name].
     * @throws [NoSuchElementException] if no such element is found.
     */
    fun first(name: String): Node = filterChildrenToNodes().first { it.nodeName == name }

    /**
     * Returns the first element matching the given [predicate].
     * @throws [NoSuchElementException] if no such element is found.
     */
    fun first(predicate: (Element) -> Boolean): Element = _children.first(predicate)

    /**
     * Returns the first element whose nodeName matches [name], or `null` if element was not found.
     */
    fun firstOrNull(name: String): Node? =
        filterChildrenToNodes().firstOrNull { it.nodeName == name }

    /**
     * Returns the first element matching the given [predicate], or `null` if element was not found.
     */
    fun firstOrNull(predicate: (Element) -> Boolean): Element? = _children.firstOrNull(predicate)

    /**
     * Returns `true` if at least one element's nodeName matches [name].
     */
    fun exists(name: String): Boolean = filterChildrenToNodes().any { it.nodeName == name }

    /**
     * Returns `true` if at least one element matches the given [predicate].
     */
    fun exists(predicate: (Element) -> Boolean): Boolean = _children.any(predicate)

    private fun filterChildrenToNodes(): List<Node> = _children.filterIsInstance<Node>()

    private fun findIndex(element: Element): Int {
        return _children
            .indexOfFirst { it === element }
            .takeUnless { it == -1 }
            ?: throw IllegalArgumentException("Element is not a child of '$nodeName'")
    }

    private fun setParentIfNode(element: Element, newParent: Node?) {
        if (element is Node) {
            element.parent = newParent
        }
    }

    override fun equals(other: Any?): Boolean {
        if (other !is Node) {
            return false
        }

        return nodeName == other.nodeName &&
                encoding == other.encoding &&
                version == other.version &&
                _attributes == other._attributes &&
                _globalLevelProcessingInstructions == other._globalLevelProcessingInstructions &&
                _children == other._children
    }

    override fun hashCode(): Int {
        var result = nodeName.hashCode()
        result = 31 * result + encoding.hashCode()
        result = 31 * result + version.hashCode()
        result = 31 * result + _attributes.hashCode()
        result = 31 * result + _globalLevelProcessingInstructions.hashCode()
        result = 31 * result + _children.hashCode()
        return result
    }
}
