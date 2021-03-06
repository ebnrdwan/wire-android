/*
 * Wire
 * Copyright (C) 2016 Wire Swiss GmbH
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program. If not, see <http://www.gnu.org/licenses/>.
 */
package com.waz.service.messages

import com.waz.api.Message.Type._
import com.waz.content.MessagesStorage
import com.waz.log.BasicLogging.LogTag.DerivedLogTag
import com.waz.log.LogSE._
import com.waz.model.GenericContent.{Asset, Calling, Cleared, DeliveryReceipt, Ephemeral, Knock, LastRead, LinkPreview, Location, MsgDeleted, MsgEdit, MsgRecall, Reaction, Text}
import com.waz.model.{GenericContent, _}
import com.waz.service.EventScheduler
import com.waz.service.assets2.{AssetService, AssetStatus, DownloadAsset, DownloadAssetStatus, DownloadAssetStorage, GeneralAsset, Asset => Asset2}
import com.waz.service.conversation.{ConversationsContentUpdater, ConversationsService}
import com.waz.threading.Threading
import com.waz.utils.crypto.ReplyHashing
import com.waz.utils.events.EventContext
import com.waz.utils.{RichFuture, _}

import scala.concurrent.Future

class MessageEventProcessor(selfUserId:           UserId,
                            storage:              MessagesStorage,
                            contentUpdater:       MessagesContentUpdater,
                            assets:               AssetService,
                            replyHashing:         ReplyHashing,
                            msgsService:          MessagesService,
                            convsService:         ConversationsService,
                            convs:                ConversationsContentUpdater,
                            downloadAssetStorage: DownloadAssetStorage) extends DerivedLogTag {
  import MessageEventProcessor._
  import Threading.Implicits.Background
  private implicit val ec = EventContext.Global

  val messageEventProcessingStage = EventScheduler.Stage[MessageEvent] { (convId, events) =>
    convs.processConvWithRemoteId(convId, retryAsync = true) { conv =>
      verbose(l"processing events for conv: $conv, events: $events")

      convsService.isGroupConversation(conv.id).flatMap { isGroup =>
        storage.blockStreams(true)

        returning(processEvents(conv, isGroup, events)){ result =>
          storage.blockStreams(false)
          result.onFailure { case e: Exception => error(l"Message event processing failed.", e) }
        }
      }
    }
  }

  private[service] def processEvents(conv: ConversationData, isGroup: Boolean, events: Seq[MessageEvent]): Future[Set[MessageData]] = {
    verbose(l"processEvents: ${conv.id} isGroup:$isGroup ${events.map(_.from)}")

    val toProcess = events.filter {
      case GenericMessageEvent(_, _, _, msg) if GenericMessage.isBroadcastMessage(msg) => false
      case e => conv.cleared.forall(_.isBefore(e.time))
    }

    for {
      eventData     <- Future.traverse(toProcess)(localDataForEvent)
      modifications =  createModifications(eventData, conv, isGroup)
      msgs          <- checkReplyHashes(modifications.collect { case m if m.message != MessageData.Empty => m.message })
      _             =  verbose(l"SYNC messages from events: ${msgs.map(m => m.id -> m.msgType)}")
      _             <- addUnexpectedMembers(conv.id, events)
      res           <- contentUpdater.addMessages(conv.id, msgs)
      _             <- Future.traverse(modifications.flatMap(_.assets))(assets.save)
      _             <- updateLastReadFromOwnMessages(conv.id, msgs)
      _             <- deleteCancelled(modifications)
      _             <- applyRecalls(conv.id, toProcess)
      _             <- applyEdits(conv.id, toProcess)
      _             =  verbose(l"SYNC processing events finished")
    } yield res
  }

  private def createModifications(eventData: Seq[(MessageEvent, Option[DownloadAsset])],
                                  conversationData: ConversationData,
                                  isGroup: Boolean): Seq[EventModifications] = {

    eventData.foldLeft(List.empty[EventModifications]) { (acc, next) =>
      val (event, downloadAsset) = next
      val modification = createModification(acc, conversationData, isGroup, event, downloadAsset)
      modification :: acc
    }
  }

  private def createModification(temporaryModifications: List[EventModifications],
                                  conv: ConversationData,
                                  isGroup: Boolean,
                                  event: MessageEvent,
                                  downloadAsset: Option[DownloadAsset]): EventModifications = {
    lazy val id = MessageId()
    event match {
      case ConnectRequestEvent(_, time, from, text, recipient, name, email) =>
        EventModifications(MessageData(id, conv.id, CONNECT_REQUEST, from, MessageData.textContent(text), recipient = Some(recipient), email = email, name = Some(name), time = time, localTime = event.localTime))
      case RenameConversationEvent(_, time, from, name) =>
        EventModifications(MessageData(id, conv.id, RENAME, from, name = Some(name), time = time, localTime = event.localTime))
      case MessageTimerEvent(_, time, from, duration) =>
        EventModifications(MessageData(id, conv.id, MESSAGE_TIMER, from, time = time, duration = duration, localTime = event.localTime))
      case MemberJoinEvent(_, time, from, userIds, users, firstEvent) =>
        EventModifications(MessageData(id, conv.id, MEMBER_JOIN, from, members = (users.map(_._1) ++ userIds).toSet, time = time, localTime = event.localTime, firstMessage = firstEvent))
      case ConversationReceiptModeEvent(_, time, from, 0) =>
        EventModifications(MessageData(id, conv.id, READ_RECEIPTS_OFF, from, time = time, localTime = event.localTime))
      case ConversationReceiptModeEvent(_, time, from, receiptMode) if receiptMode > 0 =>
        EventModifications(MessageData(id, conv.id, READ_RECEIPTS_ON, from, time = time, localTime = event.localTime))
      case MemberLeaveEvent(_, time, from, userIds) =>
        EventModifications(MessageData(id, conv.id, MEMBER_LEAVE, from, members = userIds.toSet, time = time, localTime = event.localTime))
      case OtrErrorEvent(_, time, from, IdentityChangedError(_, _)) =>
        EventModifications(MessageData(id, conv.id, OTR_IDENTITY_CHANGED, from, time = time, localTime = event.localTime))
      case OtrErrorEvent(_, time, from, _) =>
        EventModifications(MessageData(id, conv.id, OTR_ERROR, from, time = time, localTime = event.localTime))
      case GenericMessageEvent(_, time, from, proto) =>
        verbose(l"generic message event")
        val GenericMessage(uid, msgContent) = proto
        content(temporaryModifications, MessageId(uid.str), conv.id, msgContent, from, event.localTime, time, conv.receiptMode.filter(_ => isGroup), downloadAsset, proto)
      case _: CallMessageEvent =>
        EventModifications.Empty
      case _ =>
        warn(l"Unexpected event for addMessage: $event")
        EventModifications.Empty
    }
  }

  private def content(temporaryModifications: List[EventModifications],
                      id: MessageId,
                      convId: ConvId,
                      msgContent: Any,
                      from: UserId,
                      localTime: LocalInstant,
                      time: RemoteInstant,
                      forceReadReceipts: Option[Int],
                      downloadAsset: Option[DownloadAsset],
                      proto: GenericMessage
                     ): EventModifications = msgContent match {
    case Ephemeral(expiry, ct) =>
      val modifications = content(temporaryModifications, id, convId, ct, from, localTime, time, forceReadReceipts, downloadAsset, proto)
      modifications.copy(message = modifications.message.copy(ephemeral = expiry))
    case Text(text, mentions, links, quote) =>
      textEventModifications(id, convId, text, mentions, links, quote, from, localTime, time, forceReadReceipts, proto)
    case asset: Asset =>
      assetEventModifications(temporaryModifications, id, convId, asset, from, localTime, time, forceReadReceipts, downloadAsset, proto)
    case _: Knock =>
      EventModifications(MessageData(id, convId, KNOCK, from, time = time, localTime = localTime, protos = Seq(proto), forceReadReceipts = forceReadReceipts))
    case _: Location =>
      EventModifications(MessageData(id, convId, LOCATION, from, time = time, localTime = localTime, protos = Seq(proto), forceReadReceipts = forceReadReceipts))
    case _: Reaction                   => EventModifications.Empty
    case _: LastRead                   => EventModifications.Empty
    case _: Cleared                    => EventModifications.Empty
    case _: MsgDeleted                 => EventModifications.Empty
    case _: MsgRecall                  => EventModifications.Empty
    case _: MsgEdit                    => EventModifications.Empty
    case DeliveryReceipt(_)            => EventModifications.Empty
    case GenericContent.ReadReceipt(_) => EventModifications.Empty
    case _: Calling                    => EventModifications.Empty
    case _ =>
      // TODO: this message should be processed again after app update, maybe future app version will understand it
      EventModifications(MessageData(id, convId, UNKNOWN, from, time = time, localTime = localTime, protos = Seq(proto)))
  }

  /**
    * Creates safe version of incoming message.
    * Messages sent by malicious contacts might contain content intended to break the app. One example of that
    * are very long text messages, backend doesn't restrict the size much to allow for assets and group messages,
    * because of encryption it's also not possible to limit text messages there. On client such messages are handled
    * inline, and will cause memory problems.
    * We may need to do more involved checks in future.
    */
  private def textEventModifications(id:                MessageId,
                                     convId:            ConvId,
                                     originalText:      String,
                                     mentions:          Seq[Mention],
                                     linkPreviews:      Seq[LinkPreview],
                                     quote:             Option[GenericContent.Quote],
                                     from:              UserId,
                                     localTime:         LocalInstant,
                                     time:              RemoteInstant,
                                     forceReadReceipts: Option[Int],
                                     proto:             GenericMessage): EventModifications = {
    val (text, links) =
      if (originalText.length > MaxTextContentLength)
        (originalText.take(MaxTextContentLength), linkPreviews.filter(p => p.url.length + p.urlOffset <= MaxTextContentLength))
      else
        (originalText, linkPreviews)

    val (tpe, content) = MessageData.messageContent(text, mentions, links)
    val quoteContent = quote.map(q => QuoteContent(MessageId(q.quotedMessageId), validity = false, Some(Sha256(q.quotedMessageSha256))))

    val asset = links
      .find(lp => lp.image != null && lp.image.hasUploaded)
      .map(lp => Asset2.create(DownloadAsset.create(lp.image), lp.image.getUploaded))

    val messageData = MessageData(
      id, convId, tpe, from, content, time = time, localTime = localTime, protos = Seq(proto),
      quote = quoteContent, forceReadReceipts = forceReadReceipts, assetId = asset.map(_.id)
    )
    EventModifications(messageData.adjustMentions(false).getOrElse(messageData), asset.map((_, None)))
  }

  private def assetEventModifications(temporaryModifications: List[EventModifications],
                                      id: MessageId,
                                      convId: ConvId,
                                      asset: Asset,
                                      from: UserId,
                                      localTime: LocalInstant,
                                      time: RemoteInstant,
                                      forceReadReceipts: Option[Int],
                                      downloadAsset: Option[DownloadAsset],
                                      proto: GenericMessage): EventModifications =
    if (DownloadAsset.getStatus(asset) == DownloadAssetStatus.Cancelled) EventModifications.Empty else {
      val tpe = Option(asset.original) match {
        case None                      => UNKNOWN
        case Some(org) if org.hasVideo => VIDEO_ASSET
        case Some(org) if org.hasAudio => AUDIO_ASSET
        case Some(org) if org.hasImage => IMAGE_ASSET
        case Some(_)                   => ANY_ASSET
      }

      val assetAndPreview =
        if (asset.hasUploaded) {
          val preview = Option(asset.preview).map(Asset2.create)

          lazy val previouslyProcessedDownloadAsset = temporaryModifications
            .find(_.message.id == id)
            .flatMap(_.assets.headOption)
            .map(_.asInstanceOf[DownloadAsset])

          val updatedDownloadAsset = downloadAsset.orElse(previouslyProcessedDownloadAsset)
            .map(da => da.copy(preview = preview.map(_.id).orElse(da.preview), status = AssetStatus.Done))

          val asset2 = updatedDownloadAsset match {
            case Some(x) => Asset2.create(x, asset.getUploaded)
            case None => Asset2.create(DownloadAsset.create(asset), asset.getUploaded)
          }

          verbose(l"Received asset v3 with preview.")
          Some((asset2, preview))

        } else if (DownloadAsset.getStatus(asset) == DownloadAssetStatus.Failed && asset.original.hasImage) {
          verbose(l"Received a message about a failed image upload: $id. Dropping")
          None

        } else if (DownloadAsset.getStatus(asset) == DownloadAssetStatus.Cancelled) {
          verbose(l"Uploader cancelled asset: $id")
          val asset2 = downloadAsset.map(_.copy(status = DownloadAssetStatus.Cancelled)).getOrElse(DownloadAsset.create(asset))
          Some((asset2, None))

        } else {
          val preview = Option(asset.preview).map(Asset2.create)
          val asset2 = downloadAsset
            .map(da => da.copy(preview = preview.map(_.id).orElse(da.preview), status = DownloadAsset.getStatus(asset)))
            .getOrElse(DownloadAsset.create(asset))

          verbose(l"Received asset without remote data - we will expect another update. Asset: $asset2")
          Some((asset2, preview))
        }

      EventModifications(
        MessageData(
          id, convId, tpe, from, time = time, localTime = localTime, protos = Seq(proto),
          forceReadReceipts = forceReadReceipts, assetId = assetAndPreview.map(_._1.id)
        ),
        assetAndPreview
      )
    }

  private def checkReplyHashes(msgs: Seq[MessageData]) = {
    val (standard, quotes) = msgs.partition(_.quote.isEmpty)

    for {
      originals     <- storage.getMessages(quotes.flatMap(_.quote.map(_.message)): _*)
      hashes        <- replyHashing.hashMessages(originals.flatten)
      updatedQuotes =  quotes.map(q => q.quote match {
        case Some(QuoteContent(message, validity, hash)) if hashes.contains(message) =>
          val newValidity = hash.contains(hashes(message))
          if (validity != newValidity) q.copy(quote = Some(QuoteContent(message, newValidity, hash) )) else q
        case _ => q
      })
    } yield standard ++ updatedQuotes
  }

  private def localDataForEvent(event: MessageEvent) = {
    for {
      message <- event match {
        case GenericMessageEvent(_, _, _, c) => storage.get(MessageId(c.messageId))
        case _                               => Future.successful(None)
      }
      asset <- message.flatMap(_.assetId) match {
        case Some(dId: DownloadAssetId) => downloadAssetStorage.find(dId)
        case _                          => Future.successful(None)
      }
    } yield (event, asset)
  }

  private def addUnexpectedMembers(convId: ConvId, events: Seq[MessageEvent]) = {
    val potentiallyUnexpectedMembers = events.filter {
      case e: MemberLeaveEvent if e.userIds.contains(e.from) => false
      case _ => true
    }.map(_.from).toSet
    if (potentiallyUnexpectedMembers.nonEmpty)
      convsService.addUnexpectedMembersToConv(convId, potentiallyUnexpectedMembers)
    else Future.successful(())
  }

  private def applyRecalls(convId: ConvId, toProcess: Seq[MessageEvent]) = {
    import com.waz.api.Message.Status.SENT
    val recalls = toProcess collect {
      case GenericMessageEvent(_, time, from, msg @ GenericMessage(_, MsgRecall(_))) => (msg, from, time)
    }
    Future.traverse(recalls) {
      case (GenericMessage(id, MsgRecall(ref)), user, time) =>
        msgsService.recallMessage(convId, ref, user, MessageId(id.str), time, SENT)
    }
  }

  // TODO: handle mentions in case of MsgEdit
  private def applyEdits(convId: ConvId, toProcess: Seq[MessageEvent]) = {
    val edits = toProcess collect {
      case GenericMessageEvent(_, time, from, msg @ GenericMessage(_, MsgEdit(_, _))) => (msg, from, time)
    }
    RichFuture.traverseSequential(edits) {
      case (gm @ GenericMessage(_, MsgEdit(_, Text(_, _, _, _))), user, time) =>
        msgsService.applyMessageEdit(convId, user, time, gm)
    }
  }

  private def deleteCancelled(modifications: Seq[EventModifications]): Future[Unit] = {
    val toRemove = modifications.filter {
      _.assetWithPreview match {
        case Some((asset: DownloadAsset, _)) => asset.status == DownloadAssetStatus.Cancelled
        case _ => false
      }
    }

    for {
      _ <- Future.traverse(toRemove.map(_.message))(msg => storage.remove(msg.id))
      _ <- Future.traverse(toRemove.flatMap(_.assets))(asset => assets.delete(asset.id))
    } yield ()
  }

  private def updateLastReadFromOwnMessages(convId: ConvId, msgs: Seq[MessageData]) =
    msgs.reverseIterator.find(_.userId == selfUserId).fold2(Future.successful(None), msg => convs.updateConversationLastRead(convId, msg.time))
}

object MessageEventProcessor {
  val MaxTextContentLength = 8192

  case class EventModifications(message: MessageData,
                                assetWithPreview: Option[(GeneralAsset, Option[GeneralAsset])] = None) {

    lazy val assets: List[GeneralAsset] = assetWithPreview match {
      case Some((asset, Some(preview))) => List(asset, preview)
      case Some((asset, None))          => List(asset)
      case None                         => Nil
    }
  }

  object EventModifications {
    val Empty = EventModifications(MessageData.Empty)
  }
}
