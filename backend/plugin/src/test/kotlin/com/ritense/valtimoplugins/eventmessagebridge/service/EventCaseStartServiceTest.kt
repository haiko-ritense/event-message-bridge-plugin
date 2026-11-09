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

import com.fasterxml.jackson.databind.ObjectMapper
import com.ritense.case.service.CaseDefinitionService
import com.ritense.case_.domain.definition.CaseDefinition
import com.ritense.document.domain.Document
import com.ritense.document.domain.impl.JsonSchemaDocumentId
import com.ritense.document.service.DocumentService
import com.ritense.document.service.result.CreateDocumentResult
import com.ritense.plugin.domain.PluginProcessLink
import com.ritense.processdocument.domain.ProcessDefinitionCaseDefinition
import com.ritense.processdocument.domain.ProcessDefinitionCaseDefinitionId
import com.ritense.processdocument.domain.ProcessDefinitionId
import com.ritense.processdocument.domain.ProcessDocumentInstance
import com.ritense.processdocument.service.ProcessDefinitionCaseDefinitionService
import com.ritense.processdocument.service.ProcessDocumentAssociationService
import com.ritense.processlink.domain.ActivityTypeWithEventName
import com.ritense.processlink.repository.ValtimoPluginProcessLinkRepository
import com.ritense.valtimo.contract.case_.CaseDefinitionId
import com.ritense.valtimoplugins.eventmessagebridge.BaseTest
import com.ritense.valtimoplugins.eventmessagebridge.repository.ProcessedEventRepository
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.doReturn
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import org.operaton.bpm.engine.RepositoryService
import org.operaton.bpm.engine.RuntimeService
import org.operaton.bpm.engine.repository.ProcessDefinition
import org.operaton.bpm.engine.runtime.MessageCorrelationBuilder
import org.operaton.bpm.engine.runtime.ProcessInstance
import org.operaton.bpm.model.bpmn.BpmnModelInstance
import org.operaton.bpm.model.bpmn.instance.CatchEvent
import org.operaton.bpm.model.bpmn.instance.Message
import org.operaton.bpm.model.bpmn.instance.MessageEventDefinition
import org.operaton.bpm.model.bpmn.instance.Process
import org.operaton.bpm.model.xml.instance.ModelElementInstance
import org.semver4j.Semver
import java.util.Optional
import java.util.UUID

class EventCaseStartServiceTest : BaseTest() {
    private lateinit var pluginProcessLinkRepository: ValtimoPluginProcessLinkRepository
    private lateinit var runtimeService: RuntimeService
    private lateinit var repositoryService: RepositoryService
    private lateinit var processDefinitionCaseDefinitionService: ProcessDefinitionCaseDefinitionService
    private lateinit var documentService: DocumentService
    private lateinit var processDocumentAssociationService: ProcessDocumentAssociationService
    private lateinit var caseDefinitionService: CaseDefinitionService
    private lateinit var processedEventRepository: ProcessedEventRepository
    private lateinit var service: EventCaseStartService

    @BeforeEach
    fun setUp() {
        pluginProcessLinkRepository = mock()
        runtimeService = mock()
        repositoryService = mock()
        processDefinitionCaseDefinitionService = mock()
        documentService = mock()
        processDocumentAssociationService = mock()
        caseDefinitionService = mock()
        processedEventRepository = mock()
        doReturn(Optional.empty<ProcessDocumentInstance>())
            .whenever(processDocumentAssociationService)
            .findProcessDocumentInstance(any())

        service =
            EventCaseStartService(
                pluginProcessLinkRepository,
                runtimeService,
                repositoryService,
                processDefinitionCaseDefinitionService,
                documentService,
                processDocumentAssociationService,
                caseDefinitionService,
                ObjectMapper(),
                processedEventRepository,
            )
    }

    @Test
    fun `should start a case for a link on the event's event item and return its business key`() {
        val link = linked(link(EVENT_ITEM_ID))
        givenACaseCanBeStartedFor(link)
        val correlation = givenStartMessageCorrelates()

        val result = service.handle(event())

        verify(correlation).processDefinitionId(PROCESS_DEFINITION_ID)
        verify(correlation).processInstanceBusinessKey(DOCUMENT_ID.toString())
        verify(correlation).correlateStartMessage()
        verify(processDocumentAssociationService).createProcessDocumentInstance(
            eq(PROCESS_INSTANCE_ID),
            eq(DOCUMENT_ID.id),
            eq("Verwerk event"),
        )
        assertEquals(listOf(StartedCase(link.id, PROCESS_INSTANCE_ID, DOCUMENT_ID.toString())), result.startedCases)
    }

    @Test
    fun `should not start a case for a link on another event item`() {
        linked(link("another-event-item"))

        val result = service.handle(event())

        assertTrue(result.startedCases.isEmpty())
        verify(runtimeService, never()).createMessageCorrelation(any())
        verify(processedEventRepository, never()).saveAndFlush(any())
    }

    @Test
    fun `should not start a case for a link without an event item`() {
        linked(link(null))

        service.handle(event())

        verify(runtimeService, never()).createMessageCorrelation(any())
    }

    @Test
    fun `should record an event and skip it when it is delivered again`() {
        val link = linked(link(EVENT_ITEM_ID))
        givenACaseCanBeStartedFor(link)
        givenStartMessageCorrelates()
        service.handle(event())
        verify(processedEventRepository).saveAndFlush(any())

        whenever(processedEventRepository.existsById(EVENT_ID)).thenReturn(true)
        val second = service.handle(event())

        assertTrue(second.duplicate)
        verify(documentService, org.mockito.kotlin.times(1)).createDocument(any())
    }

    @Test
    fun `should skip a case process that may not initialise a document instead of failing`() {
        val link = linked(link(EVENT_ITEM_ID))
        givenACaseCanBeStartedFor(link, canInitializeDocument = false)

        val result = service.handle(event())

        assertTrue(result.startedCases.isEmpty())
        verify(documentService, never()).createDocument(any())
    }

    @Test
    fun `should skip a case process whose case definition version is no longer active`() {
        val link = linked(link(EVENT_ITEM_ID))
        givenACaseCanBeStartedFor(link)
        whenever(caseDefinitionService.getActiveCaseDefinition(CASE_KEY)).thenReturn(caseDefinition("2.0.0"))

        service.handle(event())

        verify(documentService, never()).createDocument(any())
    }

    @Test
    fun `should skip a process that is linked to no case definition`() {
        val link = linked(link(EVENT_ITEM_ID))
        givenACaseCanBeStartedFor(link)
        whenever(processDefinitionCaseDefinitionService.findByProcessDefinitionIdOrNull(any())).thenReturn(null)

        service.handle(event())

        verify(documentService, never()).createDocument(any())
    }

    @Test
    fun `should skip a start event of an event subprocess`() {
        val link = linked(link(EVENT_ITEM_ID))
        givenACaseCanBeStartedFor(link)
        val startEvent = mock<ModelElementInstance>()
        whenever(startEvent.parentElement).thenReturn(mock<ModelElementInstance>())
        whenever(bpmnModel.getModelElementById<ModelElementInstance>(ACTIVITY_ID)).thenReturn(startEvent)

        service.handle(event())

        verify(documentService, never()).createDocument(any())
    }

    private val bpmnModel = mock<BpmnModelInstance>()

    private fun event() = EmbEvent(eventId = EVENT_ID, eventItemId = EVENT_ITEM_ID)

    private fun linked(vararg links: PluginProcessLink): PluginProcessLink {
        whenever(pluginProcessLinkRepository.findByPluginActionDefinitionKey("receive-emb-event")).thenReturn(links.toList())
        return links.first()
    }

    private fun link(eventItemId: String?): PluginProcessLink {
        val actionProperties = eventItemId?.let { ObjectMapper().createObjectNode().put("eventItemId", it) }
        return mock<PluginProcessLink>().also {
            whenever(it.id).thenReturn(UUID.randomUUID())
            whenever(it.activityType).thenReturn(ActivityTypeWithEventName.MESSAGE_START_EVENT_START)
            whenever(it.activityId).thenReturn(ACTIVITY_ID)
            whenever(it.processDefinitionId).thenReturn(PROCESS_DEFINITION_ID)
            whenever(it.actionProperties).thenReturn(actionProperties)
        }
    }

    private fun caseDefinition(version: String) =
        CaseDefinition(
            id = CaseDefinitionId(CASE_KEY, Semver(version)),
            name = "Events",
            createdDate = null,
            canHaveAssignee = true,
        )

    /** A message start event of the process itself, in the active version of a case definition, whose document can be created. */
    private fun givenACaseCanBeStartedFor(
        link: PluginProcessLink,
        canInitializeDocument: Boolean = true,
    ) {
        // Real objects rather than mocks: these are JPA entities whose `val`s Mockito cannot stub.
        val processDefinitionCaseDefinition =
            ProcessDefinitionCaseDefinition(
                ProcessDefinitionCaseDefinitionId(
                    ProcessDefinitionId(link.processDefinitionId),
                    CaseDefinitionId(CASE_KEY, Semver("1.0.0")),
                ),
                canInitializeDocument = canInitializeDocument,
            )

        // Each mock is finished before the next stubbing starts, to avoid unfinished stubbing.
        val message = mock<Message>()
        whenever(message.name).thenReturn("emb-event")
        val messageEventDefinition = mock<MessageEventDefinition>()
        whenever(messageEventDefinition.message).thenReturn(message)
        val startEvent = mock<CatchEvent>()
        whenever(startEvent.eventDefinitions).thenReturn(listOf(messageEventDefinition))
        whenever(startEvent.parentElement).thenReturn(mock<Process>())
        whenever(bpmnModel.getModelElementById<ModelElementInstance>(ACTIVITY_ID)).thenReturn(startEvent)
        whenever(bpmnModel.getModelElementById<CatchEvent>(ACTIVITY_ID)).thenReturn(startEvent)
        whenever(repositoryService.getBpmnModelInstance(PROCESS_DEFINITION_ID)).thenReturn(bpmnModel)

        val processDefinition = mock<ProcessDefinition>()
        whenever(processDefinition.name).thenReturn("Verwerk event")
        whenever(repositoryService.getProcessDefinition(PROCESS_DEFINITION_ID)).thenReturn(processDefinition)

        val document = mock<Document>()
        whenever(document.id()).thenReturn(DOCUMENT_ID)
        val createResult = mock<CreateDocumentResult>()
        doReturn(Optional.of(document)).whenever(createResult).resultingDocument()

        whenever(processDefinitionCaseDefinitionService.findByProcessDefinitionIdOrNull(any()))
            .thenReturn(processDefinitionCaseDefinition)
        whenever(caseDefinitionService.getActiveCaseDefinition(CASE_KEY)).thenReturn(caseDefinition("1.0.0"))
        whenever(documentService.createDocument(any())).thenReturn(createResult)
    }

    private fun givenStartMessageCorrelates(): MessageCorrelationBuilder {
        val processInstance = mock<ProcessInstance>()
        whenever(processInstance.id).thenReturn(PROCESS_INSTANCE_ID)
        val correlation = mock<MessageCorrelationBuilder>()
        whenever(correlation.processDefinitionId(any())).thenReturn(correlation)
        whenever(correlation.processInstanceBusinessKey(any())).thenReturn(correlation)
        whenever(correlation.setVariables(any())).thenReturn(correlation)
        whenever(correlation.correlateStartMessage()).thenReturn(processInstance)
        whenever(runtimeService.createMessageCorrelation("emb-event")).thenReturn(correlation)
        return correlation
    }

    companion object {
        private const val EVENT_ID = "event-1"
        private const val EVENT_ITEM_ID = "4cd89296-ac05-49c2-abb6-50f566773bb9"
        private const val CASE_KEY = "events"
        private const val ACTIVITY_ID = "StartEvent_emb"
        private const val PROCESS_DEFINITION_ID = "verwerk-event:1:1"
        private var PROCESS_INSTANCE_ID = UUID.randomUUID().toString()
        private val DOCUMENT_ID = JsonSchemaDocumentId.existingId(UUID.fromString("0a6f1b5e-8a0c-4c5e-9c6e-3d7f2b1a9e11"))
    }
}
