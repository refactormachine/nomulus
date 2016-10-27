// Copyright 2016 The Nomulus Authors. All Rights Reserved.
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
//     http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.

package google.registry.flows.contact;

import static google.registry.flows.FlowUtils.validateClientIsLoggedIn;
import static google.registry.flows.ResourceFlowUtils.denyPendingTransfer;
import static google.registry.flows.ResourceFlowUtils.loadAndVerifyExistence;
import static google.registry.flows.ResourceFlowUtils.verifyOptionalAuthInfoForResource;
import static google.registry.flows.contact.ContactFlowUtils.createLosingTransferPollMessage;
import static google.registry.flows.contact.ContactFlowUtils.createTransferResponse;
import static google.registry.model.eppoutput.Result.Code.SUCCESS;
import static google.registry.model.ofy.ObjectifyService.ofy;

import com.google.common.base.Optional;
import com.googlecode.objectify.Key;
import google.registry.flows.EppException;
import google.registry.flows.ExtensionManager;
import google.registry.flows.Flow;
import google.registry.flows.FlowModule.ClientId;
import google.registry.flows.FlowModule.TargetId;
import google.registry.flows.TransactionalFlow;
import google.registry.flows.exceptions.NotPendingTransferException;
import google.registry.flows.exceptions.NotTransferInitiatorException;
import google.registry.model.contact.ContactResource;
import google.registry.model.domain.metadata.MetadataExtension;
import google.registry.model.eppcommon.AuthInfo;
import google.registry.model.eppinput.ResourceCommand;
import google.registry.model.eppoutput.EppOutput;
import google.registry.model.poll.PollMessage;
import google.registry.model.reporting.HistoryEntry;
import google.registry.model.transfer.TransferData;
import google.registry.model.transfer.TransferStatus;
import javax.inject.Inject;

/**
 * An EPP flow that cancels a pending transfer on a contact.
 *
 * <p>The "gaining" registrar requests a transfer from the "losing" (aka current) registrar. The
 * losing registrar has a "transfer" time period to respond (by default five days) after which the
 * transfer is automatically approved. Within that window, this flow allows the gaining client to
 * withdraw the transfer request.
 *
 * @error {@link google.registry.flows.ResourceFlowUtils.BadAuthInfoForResourceException}
 * @error {@link google.registry.flows.ResourceFlowUtils.ResourceDoesNotExistException}
 * @error {@link google.registry.flows.exceptions.NotPendingTransferException}
 * @error {@link google.registry.flows.exceptions.NotTransferInitiatorException}
 */
public final class ContactTransferCancelFlow extends Flow implements TransactionalFlow {

  @Inject ResourceCommand resourceCommand;
  @Inject ExtensionManager extensionManager;
  @Inject Optional<AuthInfo> authInfo;
  @Inject @ClientId String clientId;
  @Inject @TargetId String targetId;
  @Inject HistoryEntry.Builder historyBuilder;
  @Inject ContactTransferCancelFlow() {}

  @Override
  protected final EppOutput run() throws EppException {
    extensionManager.register(MetadataExtension.class);
    extensionManager.validate();
    validateClientIsLoggedIn(clientId);
    ContactResource existingContact = loadAndVerifyExistence(ContactResource.class, targetId, now);
    verifyOptionalAuthInfoForResource(authInfo, existingContact);
    TransferData transferData = existingContact.getTransferData();
    if (transferData.getTransferStatus() != TransferStatus.PENDING) {
      throw new NotPendingTransferException(targetId);
    }
    if (!clientId.equals(transferData.getGainingClientId())) {
      throw new NotTransferInitiatorException();
    }
    ContactResource newContact =
        denyPendingTransfer(existingContact, TransferStatus.CLIENT_CANCELLED, now);
    HistoryEntry historyEntry = historyBuilder
        .setType(HistoryEntry.Type.CONTACT_TRANSFER_CANCEL)
        .setModificationTime(now)
        .setParent(Key.create(existingContact))
        .build();
    // Create a poll message for the losing client.
    PollMessage losingPollMessage =
        createLosingTransferPollMessage(targetId, newContact.getTransferData(), historyEntry);
    ofy().save().<Object>entities(newContact, historyEntry, losingPollMessage);
    // Delete the billing event and poll messages that were written in case the transfer would have
    // been implicitly server approved.
    ofy().delete().keys(transferData.getServerApproveEntities());
    return createOutput(SUCCESS, createTransferResponse(targetId, newContact.getTransferData()));
  }
}
