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

package com.ritense.valtimoplugins.eventmessagebridge.plugin

import com.ritense.plugin.annotation.Plugin
import com.ritense.plugin.annotation.PluginAction
import com.ritense.plugin.annotation.PluginActionProperty
import com.ritense.processlink.domain.ActivityTypeWithEventName.MESSAGE_START_EVENT_START
import io.github.oshai.kotlinlogging.KotlinLogging

/**
 * Starts a case when the Event Message Bridge (EMB) receives an event for one of its event items.
 * The key must equal the pluginId in the plugin's frontend specification.
 */
@Plugin(
    key = "event-message-bridge",
    title = "Event Message Bridge",
    description = "Starts a case when the Event Message Bridge receives an event",
)
open class EventMessageBridgePlugin {
    /**
     * Never runs a process: the process link itself is the subscription. EventCaseStartService
     * starts the linked process when EMB reports an event for [eventItemId].
     */
    @PluginAction(
        key = RECEIVE_EVENT_ACTION_KEY,
        title = "Receive EMB event",
        description = "Starts a case of this process when the Event Message Bridge receives an event for an event item",
        activityTypes = [MESSAGE_START_EVENT_START],
    )
    open fun receiveEvent(
        @PluginActionProperty eventItemId: String?,
    ) {
        logger.debug { "Receive EMB event action registered for event item $eventItemId" }
    }

    companion object {
        // Specific to this plugin: links are looked up by action key alone.
        const val RECEIVE_EVENT_ACTION_KEY = "receive-emb-event"
        private val logger = KotlinLogging.logger {}
    }
}
