/*
 * Copyright 2026 Ritense BV, the Netherlands.
 *
 * Licensed under EUPL, Version 1.2 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * https://joinup.ec.europa.eu/collection/eupl/eupl-text-eupl-12
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" basis,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.ritense.valtimoplugins.eventmessagebridge.service

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.node.JsonNodeFactory
import com.ritense.authorization.AuthorizationContext
import com.ritense.case.service.CaseDefinitionService
import com.ritense.document.domain.impl.request.NewDocumentRequest
import com.ritense.document.service.DocumentService
import com.ritense.plugin.domain.PluginProcessLink
import com.ritense.processdocument.domain.ProcessDefinitionId
import com.ritense.processdocument.domain.impl.OperatonProcessInstanceId
import com.ritense.processdocument.service.ProcessDefinitionCaseDefinitionService
import com.ritense.processdocument.service.ProcessDocumentAssociationService
import com.ritense.processlink.domain.ActivityTypeWithEventName
import com.ritense.processlink.repository.ValtimoPluginProcessLinkRepository
import com.ritense.valtimoplugins.eventmessagebridge.domain.ProcessedEvent
import com.ritense.valtimoplugins.eventmessagebridge.domain.ReceiveEventProperties
import com.ritense.valtimoplugins.eventmessagebridge.plugin.EventMessageBridgePlugin.Companion.RECEIVE_EVENT_ACTION_KEY
import com.ritense.valtimoplugins.eventmessagebridge.repository.ProcessedEventRepository
import io.github.oshai.kotlinlogging.KotlinLogging
import org.operaton.bpm.engine.RepositoryService
import org.operaton.bpm.engine.RuntimeService
import org.operaton.bpm.model.bpmn.instance.CatchEvent
import org.operaton.bpm.model.bpmn.instance.MessageEventDefinition
import org.operaton.bpm.model.bpmn.instance.Process
import org.operaton.bpm.model.xml.instance.ModelElementInstance
import org.springframework.transaction.annotation.Transactional
import java.util.UUID

/** An event EMB received for one of its event items. */
data class EmbEvent(
    /** Unique per event; a second call with the same id starts nothing. */
    val eventId: String,
    val eventItemId: String,
    val data: JsonNode? = null,
)

/** A case started for an event. [businessKey] is the id of the case document. */
data class StartedCase(
    val processLinkId: UUID,
    val processInstanceId: String,
    val businessKey: String,
)

data class EventResult(
    /** True when the event was handled before; nothing was started this time. */
    val duplicate: Boolean,
    val startedCases: List<StartedCase>,
)

/**
 * Starts a case for every receive-emb-event process link on a message start event whose
 * eventItemId is the event's. Matching uses only that property, never the BPMN message name.
 * Ported from the cloud-event-plugin's CloudEventProcessLinkListener, for message start events only.
 */
open class EventCaseStartService(
    private val pluginProcessLinkRepository: ValtimoPluginProcessLinkRepository,
    private val runtimeService: RuntimeService,
    private val repositoryService: RepositoryService,
    private val processDefinitionCaseDefinitionService: ProcessDefinitionCaseDefinitionService,
    private val documentService: DocumentService,
    private val processDocumentAssociationService: ProcessDocumentAssociationService,
    private val caseDefinitionService: CaseDefinitionService,
    private val objectMapper: ObjectMapper,
    private val processedEventRepository: ProcessedEventRepository,
) {
    // One transaction: when a case cannot be started, the processed-event row rolls back with it, so EMB can retry.
    @Transactional
    open fun handle(event: EmbEvent): EventResult {
        val processLinks =
            pluginProcessLinkRepository
                .findByPluginActionDefinitionKey(RECEIVE_EVENT_ACTION_KEY)
                .filter { it.activityType == ActivityTypeWithEventName.MESSAGE_START_EVENT_START }
                .filter { eventItemIdOf(it) == event.eventItemId }
        if (processLinks.isEmpty()) {
            logger.debug { "EMB event '${event.eventId}' for event item '${event.eventItemId}' matched no process link" }
            return EventResult(duplicate = false, startedCases = emptyList())
        }

        if (processedEventRepository.existsById(event.eventId)) {
            logger.debug { "Skipping duplicate EMB event '${event.eventId}'" }
            return EventResult(duplicate = true, startedCases = emptyList())
        }
        // Inserted and flushed now, so a concurrent delivery of the same event fails on this row
        // before it acts on anything, rather than after.
        processedEventRepository.saveAndFlush(ProcessedEvent(eventId = event.eventId))

        val variables = buildProcessVariables(event)
        return EventResult(
            duplicate = false,
            startedCases = processLinks.mapNotNull { startCase(it, variables) },
        )
    }

    private fun eventItemIdOf(processLink: PluginProcessLink): String? =
        processLink.actionProperties
            ?.let { objectMapper.treeToValue(it, ReceiveEventProperties::class.java) }
            ?.eventItemId
            ?.trim()
            ?.takeUnless { it.isEmpty() }

    /** Null, with a log line, when the link is skipped: configuration is skipped, not thrown, so one link cannot block the others. */
    private fun startCase(
        processLink: PluginProcessLink,
        variables: Map<String, Any>,
    ): StartedCase? {
        // An event subprocess's start event has the same activity type, but no process can be started there.
        val startEvent =
            repositoryService
                .getBpmnModelInstance(processLink.processDefinitionId)
                .getModelElementById<ModelElementInstance>(processLink.activityId)
        if (startEvent?.parentElement !is Process) {
            logger.warn {
                "Skipping process link for '${processLink.processDefinitionId}': " +
                    "'${processLink.activityId}' is not a start event of the process itself"
            }
            return null
        }

        if (repositoryService.getProcessDefinition(processLink.processDefinitionId).isSuspended) {
            logger.warn { "Skipping process link for '${processLink.processDefinitionId}': the process definition is suspended" }
            return null
        }

        val processDefinitionCaseDefinition =
            processDefinitionCaseDefinitionService
                .findByProcessDefinitionIdOrNull(ProcessDefinitionId(processLink.processDefinitionId))
                ?: run {
                    logger.debug {
                        "Skipping process link for '${processLink.processDefinitionId}': no case definition is linked to it, " +
                            "typically because it is a system process or an earlier, detached version of the process"
                    }
                    return null
                }

        // Only the deployed, active version of a case definition may be started. Without this an
        // old process link would keep creating cases against a superseded version.
        val activeCaseDefinition =
            caseDefinitionService.getActiveCaseDefinition(processDefinitionCaseDefinition.id.caseDefinitionId.key)
        if (activeCaseDefinition?.id != processDefinitionCaseDefinition.id.caseDefinitionId) {
            logger.debug {
                "Skipping process link for '${processLink.processDefinitionId}': it points at a case definition version " +
                    "that is no longer active"
            }
            return null
        }

        if (!processDefinitionCaseDefinition.canInitializeDocument) {
            logger.warn {
                "Not starting a case for process definition '${processLink.processDefinitionId}': " +
                    "canInitializeDocument is false on the linked case definition"
            }
            return null
        }

        val messageName = getMessageName(processLink) ?: return null

        val documentResult =
            AuthorizationContext.runWithoutAuthorization {
                documentService.createDocument(
                    NewDocumentRequest(
                        activeCaseDefinition.id.key,
                        activeCaseDefinition.id.key,
                        activeCaseDefinition.id.versionTag.toString(),
                        JsonNodeFactory.instance.objectNode(),
                    ),
                )
            }
        val document =
            documentResult.resultingDocument().orElse(null)
                ?: run {
                    // Typically a document schema with required fields, which an empty document cannot meet.
                    logger.warn {
                        "Not starting a case for process definition '${processLink.processDefinitionId}': " +
                            "its document could not be created: ${documentResult.errors()}"
                    }
                    return null
                }
        val businessKey = document.id().toString()

        logger.info {
            "Creating a case for case definition '${activeCaseDefinition.id.key}' (${activeCaseDefinition.id.versionTag}) " +
                "by correlating start message '$messageName' to '${processLink.activityId}'"
        }
        // Correlated to the start message rather than started by process definition key: "by key"
        // enters a process at its plain start event when it also has one, so this start event and
        // its listeners would never run. The message name is read from the linked element; it is
        // never configured or matched on.
        val processInstance =
            runtimeService
                .createMessageCorrelation(messageName)
                .processDefinitionId(processLink.processDefinitionId)
                // Valtimo resolves `doc:` for a process instance from its business key, and associates
                // the instance with that document when its start event runs.
                .processInstanceBusinessKey(businessKey)
                .setVariables(variables)
                .correlateStartMessage()

        // Usually already associated by Valtimo's start event listener; only fill the gap, the same
        // way Valtimo's own start paths do.
        AuthorizationContext.runWithoutAuthorization {
            if (processDocumentAssociationService
                    .findProcessDocumentInstance(OperatonProcessInstanceId(processInstance.id))
                    .isEmpty
            ) {
                processDocumentAssociationService.createProcessDocumentInstance(
                    processInstance.id,
                    UUID.fromString(businessKey),
                    repositoryService.getProcessDefinition(processLink.processDefinitionId).let { it.name ?: it.key },
                )
            }
        }
        return StartedCase(processLink.id, processInstance.id, businessKey)
    }

    private fun buildProcessVariables(event: EmbEvent): Map<String, Any> {
        val variables =
            mutableMapOf<String, Any>(
                "embEventId" to event.eventId,
                "embEventItemId" to event.eventItemId,
            )
        event.data?.let { variables["embEventData"] = it }
        return variables
    }

    /** Null, with a warning, when the linked element has no message. */
    private fun getMessageName(processLink: PluginProcessLink): String? {
        val messageName =
            repositoryService
                .getBpmnModelInstance(processLink.processDefinitionId)
                .getModelElementById<CatchEvent>(processLink.activityId)
                ?.eventDefinitions
                ?.filterIsInstance<MessageEventDefinition>()
                ?.firstOrNull()
                ?.message
                ?.name
        if (messageName == null) {
            logger.warn {
                "Skipping process link for '${processLink.processDefinitionId}': element " +
                    "'${processLink.activityId}' is not a catch event with a message"
            }
        }
        return messageName
    }

    companion object {
        private val logger = KotlinLogging.logger {}
    }
}
