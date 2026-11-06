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

package com.ritense.valtimoplugins.eventmessagebridge.autoconfiguration

import com.fasterxml.jackson.databind.ObjectMapper
import com.ritense.case.service.CaseDefinitionService
import com.ritense.document.service.DocumentService
import com.ritense.plugin.service.PluginService
import com.ritense.processdocument.service.ProcessDefinitionCaseDefinitionService
import com.ritense.processdocument.service.ProcessDocumentAssociationService
import com.ritense.processlink.repository.ValtimoPluginProcessLinkRepository
import com.ritense.valtimo.contract.config.LiquibaseMasterChangeLogLocation
import com.ritense.valtimoplugins.eventmessagebridge.domain.ProcessedEvent
import com.ritense.valtimoplugins.eventmessagebridge.plugin.EventMessageBridgePluginFactory
import com.ritense.valtimoplugins.eventmessagebridge.repository.ProcessedEventRepository
import com.ritense.valtimoplugins.eventmessagebridge.security.EventMessageBridgeHttpSecurityConfigurer
import com.ritense.valtimoplugins.eventmessagebridge.service.EventCaseStartService
import com.ritense.valtimoplugins.eventmessagebridge.web.rest.EventResource
import org.operaton.bpm.engine.RepositoryService
import org.operaton.bpm.engine.RuntimeService
import org.springframework.boot.autoconfigure.AutoConfiguration
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.boot.autoconfigure.domain.EntityScan
import org.springframework.context.annotation.Bean
import org.springframework.core.Ordered.HIGHEST_PRECEDENCE
import org.springframework.core.annotation.Order
import org.springframework.data.jpa.repository.config.EnableJpaRepositories

@AutoConfiguration
@EnableJpaRepositories(basePackageClasses = [ProcessedEventRepository::class])
@EntityScan(basePackageClasses = [ProcessedEvent::class])
class EventMessageBridgeAutoConfiguration {
    @Bean
    @ConditionalOnMissingBean(EventMessageBridgePluginFactory::class)
    fun eventMessageBridgePluginFactory(pluginService: PluginService): EventMessageBridgePluginFactory =
        EventMessageBridgePluginFactory(pluginService)

    @Bean
    @ConditionalOnMissingBean(EventCaseStartService::class)
    fun eventCaseStartService(
        pluginProcessLinkRepository: ValtimoPluginProcessLinkRepository,
        runtimeService: RuntimeService,
        repositoryService: RepositoryService,
        processDefinitionCaseDefinitionService: ProcessDefinitionCaseDefinitionService,
        documentService: DocumentService,
        processDocumentAssociationService: ProcessDocumentAssociationService,
        caseDefinitionService: CaseDefinitionService,
        objectMapper: ObjectMapper,
        processedEventRepository: ProcessedEventRepository,
    ): EventCaseStartService =
        EventCaseStartService(
            pluginProcessLinkRepository,
            runtimeService,
            repositoryService,
            processDefinitionCaseDefinitionService,
            documentService,
            processDocumentAssociationService,
            caseDefinitionService,
            objectMapper,
            processedEventRepository,
        )

    @Bean
    @ConditionalOnMissingBean(EventResource::class)
    fun eventMessageBridgeEventResource(eventCaseStartService: EventCaseStartService): EventResource =
        EventResource(eventCaseStartService)

    @Order(400)
    @Bean
    @ConditionalOnMissingBean(EventMessageBridgeHttpSecurityConfigurer::class)
    fun eventMessageBridgeHttpSecurityConfigurer(): EventMessageBridgeHttpSecurityConfigurer =
        EventMessageBridgeHttpSecurityConfigurer()

    @Order(HIGHEST_PRECEDENCE + 34)
    @Bean
    @ConditionalOnMissingBean(name = ["eventMessageBridgeLiquibaseMasterChangeLogLocation"])
    fun eventMessageBridgeLiquibaseMasterChangeLogLocation(): LiquibaseMasterChangeLogLocation =
        LiquibaseMasterChangeLogLocation("config/liquibase/event-message-bridge-master.xml")
}
