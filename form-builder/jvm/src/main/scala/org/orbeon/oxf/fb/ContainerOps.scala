/**
 * Copyright (C) 2011 Orbeon, Inc.
 *
 * This program is free software; you can redistribute it and/or modify it under the terms of the
 * GNU Lesser General Public License as published by the Free Software Foundation; either version
 * 2.1 of the License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful, but WITHOUT ANY WARRANTY;
 * without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 * See the GNU Lesser General Public License for more details.
 *
 * The full text of the license is available at http://www.gnu.org/copyleft/lesser.html
 */
package org.orbeon.oxf.fb

import org.orbeon.oxf.fb.FormBuilder._
import org.orbeon.oxf.fb.Names._
import org.orbeon.oxf.fr.XMLNames._
import org.orbeon.oxf.util.StringUtils._
import org.orbeon.oxf.xforms.XFormsConstants.APPEARANCE_QNAME
import org.orbeon.oxf.xforms.XFormsUtils
import org.orbeon.oxf.xforms.action.XFormsAPI._
import org.orbeon.oxf.xforms.xbl.BindingDescriptor._
import org.orbeon.saxon.om.NodeInfo
import org.orbeon.scaxon.XML._

trait ContainerOps extends ControlOps {

  self: GridOps ⇒ // funky dependency, to resolve at some point

  def containerById(containerId: String): NodeInfo = {
    // Support effective id, to make it easier to use from XForms (i.e. no need to call
    // XFormsUtils.getStaticIdFromId every time)
    val staticId = XFormsUtils.getStaticIdFromId(containerId)
    findInViewTryIndex(fbFormInstance, staticId) filter IsContainer head
  }

  def controlsInContainer(containerId: String): Int = (containerById(containerId) \\ "*:td" \ *).length

  // Find all siblings of the given element with the given name, excepting the given element
  def findSiblingsWithName(element: NodeInfo, siblingName: String) =
    element parent * child * filter
      (_.name == siblingName) filterNot
      (_ == element)

  def getInitialIterationsAttribute(controlElem: NodeInfo) =
    controlElem attValueOpt InitialIterations flatMap trimAllToOpt

  // Return all the container controls in the view
  def getAllContainerControlsWithIds(inDoc: NodeInfo) = getAllControlsWithIds(inDoc) filter IsContainer

  def getAllContainerControls(inDoc: NodeInfo) = findFRBodyElement(inDoc) descendant * filter IsContainer

  // Various counts
  def countSections(inDoc: NodeInfo)         = getAllControlsWithIds(inDoc)          count IsSection
  def countAllGrids(inDoc: NodeInfo)         = findFRBodyElement(inDoc) descendant * count IsGrid
  def countRepeats(inDoc: NodeInfo)          = getAllControlsWithIds(inDoc)          count isRepeat
  def countSectionTemplates(inDoc: NodeInfo) = findFRBodyElement(inDoc) descendant * count isSectionTemplateContent

  def countGrids(inDoc: NodeInfo)            = countAllGrids(inDoc) - countRepeats(inDoc)
  def countAllNonContainers(inDoc: NodeInfo) = getAllControlsWithIds(inDoc) filterNot IsContainer size
  def countAllContainers(inDoc: NodeInfo)    = getAllContainerControls(inDoc).size
  def countAllControls(inDoc: NodeInfo)      = countAllContainers(inDoc) + countAllNonContainers(inDoc) + countSectionTemplates(inDoc)

  // A container can be removed if it's not the last one at that level
  def canDeleteContainer(container: NodeInfo): Boolean =
    container sibling FRContainerTest nonEmpty

  // Delete the entire container and contained controls
  def deleteContainerById(canDelete: NodeInfo ⇒ Boolean, containerId: String): Unit = {
    val container = containerById(containerId)
    if (canDelete(container))
      deleteContainer(container)
  }

  def deleteContainer(container: NodeInfo) = {

    val doc = container.getDocumentRoot

    // Find the new td to select if we are removing the currently selected td
    val newTdToSelect = findNewTdToSelect(container, container \\ "*:td")

    def recurse(container: NodeInfo): Seq[NodeInfo] = {

      // NOTE: Deleting is tricky because NodeInfo is mutation-averse as it keeps a node's index, among others.
      // So deleting a node under a given NodeInfo can cause the position of following siblings to be out of date
      // and cause errors. So we delete from back to front. But a safer solution should be found.

      // Go depth-first so we delete containers after all their content has been deleted
      // NOTE: Use toList to make sure we are not lazy, otherwise items might be deleted as we go!
      val children = childrenContainers(container).reverse.toList flatMap recurse

      val gridContent =
        if (IsGrid(container))
          container \\ "*:tr" \\ "*:td" \ * filter IsControl reverse
        else
          Seq.empty

      children ++ gridContent :+ container
    }

    // Start with top-level container
    val controls = recurse(container)

    //  Delete all controls in order
    controls flatMap controlElementsToDelete foreach (delete(_))

    // Update templates
    updateTemplatesCheckContainers(doc, findAncestorRepeatNames(container).to[Set])

    // Adjust selected td if needed
    newTdToSelect foreach selectTd
  }

  // Move a container based on a move function
  def moveContainer(container: NodeInfo, otherContainer: NodeInfo, move: (NodeInfo, NodeInfo) ⇒ NodeInfo): Unit = {

    // Get names before moving the container
    val nameOption      = getControlNameOpt(container)
    val otherNameOption = getControlNameOpt(otherContainer)

    val doc = container.getDocumentRoot

    // Move container control itself
    move(container, otherContainer)

    // Try to move holders and binds based on name of other element
    (nameOption, otherNameOption) match {
      case (Some(name), Some(otherName)) ⇒

        // Move data holder only
        for {
          holder      ← findDataHolders(doc, name)
          otherHolder ← findDataHolders(doc, otherName)
        } yield
          move(holder, otherHolder)

        // Move bind
        for {
          bind      ← findBindByName(doc, name)
          otherBind ← findBindByName(doc, otherName)
        } yield
          move(bind, otherBind)

        // Try to move resource elements to a good place
        // TODO: We move the container resource holder, but we should also move together the contained controls' resource holders
        def firstControl(s: Seq[NodeInfo]) =
          s find (getControlNameOpt(_).isDefined)

        def tryToMoveHolders(siblingName: String, moveOp: (NodeInfo, NodeInfo) ⇒ NodeInfo) =
          findResourceHolders(name) foreach {
            holder ⇒
              findSiblingsWithName(holder, siblingName).headOption foreach
                  (moveOp(holder, _))
          }

        val movedContainer = findInViewTryIndex(doc, container.id).get // must get new reference

        (firstControl(movedContainer preceding *), firstControl(movedContainer following *)) match {
          case (Some(preceding), _) ⇒ tryToMoveHolders(getControlName(preceding), moveElementAfter)
          case (_, Some(following)) ⇒ tryToMoveHolders(getControlName(following), moveElementBefore)
          case _ ⇒
        }

        // Moving sections can impact templates
        updateTemplates(doc, None)

      case _ ⇒
    }
  }

  // Whether it is possible to move an item into the given container
  // Currently: must be a section without section template content
  // Later: fr:tab (maybe fr:tabview), wizard
  def canMoveInto(container: NodeInfo) =
    IsSection(container) && ! (container \ * exists isSectionTemplateContent)

  // See: https://github.com/orbeon/orbeon-forms/issues/633
  def deleteSectionTemplateContentHolders(inDoc: NodeInfo) = {

    // Find data holders for all section templates
    val holders =
      for {
        section     ← findSectionsWithTemplates(findFRBodyElement(inDoc))
        controlName ← getControlNameOpt(section).toList
        holder      ← findDataHolders(inDoc, controlName)
      } yield
        holder

    // Delete all elements underneath those holders
    holders foreach { holder ⇒
      delete(holder \ *)
    }
  }

  def hasCustomIterationName(inDoc: NodeInfo, controlName: String) =
    findRepeatIterationName(inDoc, controlName) exists (isCustomIterationName(controlName, _))

  def isCustomIterationName(controlName: String, iterationName: String) =
    defaultIterationName(controlName) != iterationName

  def setRepeatProperties(
    inDoc                : NodeInfo,
    controlName          : String,
    repeat               : Boolean,
    min                  : String,
    max                  : String,
    iterationNameOrEmpty : String,
    applyDefaults        : Boolean,
    initialIterations    : String
  ): Unit =
    findControlByName(inDoc, controlName) foreach { control ⇒

      val wasRepeat = isRepeat(control)
      val oldInitialIterationsAttribute = getInitialIterationsAttribute(control)

      val minOpt = minMaxForAttribute(min)
      val maxOpt = minMaxForAttribute(max)

      val initialIterationsOpt = initialIterations.trimAllToOpt

      // Update control attributes first
      // A missing or invalid min/max value is taken as the default value: 0 for min, none for max. In both cases, we
      // don't set the attribute value. This means that in the end we only set positive integer values.
      toggleAttribute(control, "repeat",          RepeatContentToken,                              repeat)
      toggleAttribute(control, "min",             minOpt.get,                                      repeat && minOpt.isDefined)
      toggleAttribute(control, "max",             maxOpt.get,                                      repeat && maxOpt.isDefined)
      toggleAttribute(control, "template",        makeInstanceExpression(templateId(controlName)), repeat)
      toggleAttribute(control, "apply-defaults",  "true",                                          repeat && applyDefaults)
      toggleAttribute(control, InitialIterations, initialIterationsOpt.get,                        repeat && initialIterationsOpt.isDefined)

      if (! wasRepeat && repeat) {
        // Insert new bind and template

        val iterationName = iterationNameOrEmpty.trimAllToOpt getOrElse defaultIterationName(controlName)

        // Make sure there are no nested binds
        val oldNestedBinds = findBindByName(inDoc, controlName).toList / *
        delete(oldNestedBinds)

        // Insert nested iteration bind
        findControlByName(inDoc, controlName) foreach { control ⇒
          ensureBinds(inDoc, findContainerNamesForModel(control) :+ controlName :+ iterationName)
        }

        val controlBind   = findBindByName(inDoc, controlName)
        val iterationBind = controlBind.toList / *
        insert(into = iterationBind, origin = oldNestedBinds)

        // Insert nested iteration data holders
        // NOTE: There can be multiple existing data holders due to enclosing repeats
        findDataHolders(inDoc, controlName) foreach { holder ⇒
          val nestedHolders = holder / *
          delete(nestedHolders)
          insert(into = holder, origin = elementInfo(iterationName, nestedHolders))
        }

        // Update existing templates
        // NOTE: Could skip if top-level repeat
        updateTemplatesCheckContainers(inDoc, findAncestorRepeatNames(control).to[Set])

        // Ensure new template rooted at iteration
        ensureTemplateReplaceContent(inDoc, controlName, createTemplateContentFromBind(iterationBind.head, componentBindings))

      } else if (wasRepeat && ! repeat) {
        // Remove bind, holders and template

        // Move bind up
        val controlBind = findBindByName(inDoc, controlName).toList
        val oldNestedBinds = controlBind / * / *
        delete(controlBind / *)
        insert(into = controlBind, origin = oldNestedBinds)

        // Mover data holders up and keep only the first iteration
        findDataHolders(inDoc, controlName) foreach { holder ⇒
          val nestedHolders = holder / * take 1 child *
          delete(holder / *)
          insert(into = holder, origin = nestedHolders)
        }

        // Remove template
        findTemplateInstance(inDoc, controlName) foreach (delete(_))

        // Update existing templates
        updateTemplatesCheckContainers(inDoc, findAncestorRepeatNames(control).to[Set])

      } else if (repeat) {
        // Template should already exists an should have already been renamed if needed
        // MAYBE: Ensure template just in case.

        val newInitialIterationsAttribute = getInitialIterationsAttribute(control)

        if (oldInitialIterationsAttribute != newInitialIterationsAttribute)
          updateTemplatesCheckContainers(inDoc, findAncestorRepeatNames(control, includeSelf = true).to[Set])

      } else if (! repeat) {
        // Template should not exist
        // MAYBE: Delete template just in case.
      }
    }

  def renameTemplate(doc: NodeInfo, oldName: String, newName: String): Unit =
    for {
      root     ← templateRoot(doc, oldName)
      instance ← root.parentOption
    } locally {
      ensureAttribute(instance, "id", templateId(newName))
    }

  def findTemplateInstance(doc: NodeInfo, controlName: String) =
    instanceElement(doc, templateId(controlName))

  def ensureTemplateReplaceContent(inDoc: NodeInfo, controlName: String, content: NodeInfo): Unit = {

    val templateInstanceId = templateId(controlName)
    val modelElement = findModelElement(inDoc)
    modelElement \ "*:instance" find (hasIdValue(_, templateInstanceId)) match {
      case Some(templateInstance) ⇒
        // clear existing template instance content
        delete(templateInstance \ *)
        insert(into = templateInstance , origin = content)

      case None ⇒
        // Insert template instance if not present
        val template: NodeInfo =
          <xf:instance
            xmlns:xf="http://www.w3.org/2002/xforms"
            xmlns:fb="http://orbeon.org/oxf/xml/form-builder"
            xmlns:xxf="http://orbeon.org/oxf/xml/xforms"
            id={templateInstanceId}
            fb:readonly="true"
            xxf:exclude-result-prefixes="#all">{nodeInfoToElem(content)}</xf:instance>

        insert(into = modelElement, after = modelElement \ "*:instance" takeRight 1, origin = template)
    }
  }

  // Create template content from a bind name
  //@XPathFunction
  // FIXME: Saxon can pass null as `bindings`.
  def createTemplateContentFromBindNameXPath(inDoc: NodeInfo, name: String, bindings: List[NodeInfo]): Option[NodeInfo] =
    createTemplateContentFromBindName(inDoc, name, Option(bindings) getOrElse Nil)

  def createTemplateContentFromBindName(inDoc: NodeInfo, bindName: String, bindings: Seq[NodeInfo]): Option[NodeInfo] =
    findBindByName(inDoc, bindName) map (createTemplateContentFromBind(_, bindings))

  private val AttributeRe = "@(.+)".r

  // Create an instance template based on a hierarchy of binds rooted at the given bind
  // This checks each control binding in case the control specifies a custom data holder.
  def createTemplateContentFromBind(bind: NodeInfo, bindings: Seq[NodeInfo]): NodeInfo = {

    val inDoc       = bind.getDocumentRoot
    val descriptors = getAllRelevantDescriptors(bindings)

    def holderForBind(bind: NodeInfo, topLevel: Boolean): Option[NodeInfo] = {

      val controlName    = getBindNameOrEmpty(bind)
      val controlElemOpt = findControlByName(inDoc, controlName)

      // Handle non-standard cases, see https://github.com/orbeon/orbeon-forms/issues/2470
      def fromNonStandardRef =
        bind attValueOpt "ref" match {
          case Some(AttributeRe(att)) ⇒ Some(Some(attributeInfo(att)))
          case Some(".")              ⇒ Some(None)
          case _                      ⇒ None
        }

      def fromBinding =
        for {
          controlElem ← controlElemOpt
          appearances = controlElem attTokens APPEARANCE_QNAME
          descriptor  ← findMostSpecificWithoutDatatype(controlElem.uriQualifiedName, appearances, descriptors)
          binding     ← descriptor.binding
        } yield {
          Some(FormBuilder.newDataHolder(controlName, binding))
        }

      def fromPlainControlName =
        Some(Some(elementInfo(controlName)))

      val elementTemplateOpt = fromNonStandardRef orElse fromBinding orElse fromPlainControlName flatten

      elementTemplateOpt foreach { elementTemplate ⇒

        val iterationCount = {

          // If the current control is a repeated fr:grid or fr:section with the attribute set, find the first occurrence
          // in the data of this  repeat, and use its concrete initial number of iterations to update the template. We
          // can imagine other values for the attribute in the future, maybe an integer value (`0`, `1`, ...) setting
          // the initial number of iterations.
          // See https://github.com/orbeon/orbeon-forms/issues/2379
          def useInitialIterations(controlElem: NodeInfo) =
            ! topLevel && isRepeat(controlElem) && getInitialIterationsAttribute(controlElem).contains("first")

          controlElemOpt match {
            case Some(controlElem) if useInitialIterations(controlElem) ⇒

              val firstDataHolder   = findDataHolders(inDoc, controlName) take 1
              val iterationsHolders = firstDataHolder / *

              iterationsHolders.size

            case _ ⇒
              1
          }
        }

        // Recursively insert elements in the template
        if (iterationCount > 0) {

          // If iterationCount > 1, we just duplicate the children `iterationCount` times. In practice, this means
          // multiple iteration elements:
          //
          // <repeated-section-2-iteration>
          //   ...
          // </repeated-section-2-iteration>
          // <repeated-section-2-iteration>
          //   ...
          // </repeated-section-2-iteration>
          val nested         = bind / "*:bind" flatMap (holderForBind(_, topLevel = false))
          val repeatedNested = (1 to iterationCount) flatMap (_ ⇒ nested)

          insert(into = elementTemplate, origin = repeatedNested)
        }
      }

      elementTemplateOpt
    }

    holderForBind(bind, topLevel = true) getOrElse (throw new IllegalStateException)
  }

  // Make sure all template instances reflect the current bind structure
  def updateTemplates(inDoc: NodeInfo, ancestorContainerNames: Option[Set[String]]): Unit =
    for {
      templateInstance ← templateInstanceElements(inDoc)
      repeatName       = controlNameFromId(templateInstance.id)
      if ancestorContainerNames.isEmpty || ancestorContainerNames.exists(_(repeatName))
      iterationName    ← findRepeatIterationName(inDoc, repeatName)
      template         ← createTemplateContentFromBindName(inDoc, iterationName, componentBindings)
    } locally {
      ensureTemplateReplaceContent(inDoc, repeatName, template)
    }

  // Update templates but only those which might contain one of specified names
  def updateTemplatesCheckContainers(inDoc: NodeInfo, ancestorContainerNames: Set[String]): Unit =
    updateTemplates(inDoc, Some(ancestorContainerNames))

  // This is called when the user adds/removes an iteration, as we want to update the templates in this case in order
  // to adjust the default number of iterations. See https://github.com/orbeon/orbeon-forms/issues/2379
  //@XPathFunction
  def updateTemplatesFromDynamicIterationChange(controlName: String): Unit = {

    val inDoc = getFormDoc

    findControlByName(inDoc, controlNameFromId(controlName)) foreach { controlElem ⇒
      assert(isRepeat(controlElem))
      updateTemplatesCheckContainers(inDoc, findAncestorRepeatNames(controlElem).to[Set])
    }
  }
}