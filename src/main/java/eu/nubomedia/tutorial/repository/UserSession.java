/*
 * (C) Copyright 2016 NUBOMEDIA (http://www.nubomedia.eu)
 *
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the GNU Lesser General Public License
 * (LGPL) version 2.1 which accompanies this distribution, and is available at
 * http://www.gnu.org/licenses/lgpl-2.1.html
 *
 * This library is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU
 * Lesser General Public License for more details.
 *
 */
package eu.nubomedia.tutorial.repository;

import java.util.Collections;
import java.util.Map;

import org.kurento.client.EndOfStreamEvent;
import org.kurento.client.ErrorEvent;
import org.kurento.client.EventListener;
import org.kurento.client.IceCandidate;
import org.kurento.client.KurentoClient;
import org.kurento.client.MediaPipeline;
import org.kurento.client.MediaProfileSpecType;
import org.kurento.client.OnIceCandidateEvent;
import org.kurento.client.PlayerEndpoint;
import org.kurento.client.RecorderEndpoint;
import org.kurento.client.WebRtcEndpoint;
import org.kurento.jsonrpc.JsonUtils;
import org.kurento.repository.RepositoryClient;
import org.kurento.repository.service.pojo.RepositoryItemPlayer;
import org.kurento.repository.service.pojo.RepositoryItemRecorder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;

import com.google.gson.JsonObject;

/**
 * User session.
 *
 * @author Boni Garcia (boni.garcia@urjc.es)
 * @since 6.4.1
 */
public class UserSession {

  private static final int REPOSITORY_GUARD_TIME_MS = 10000;

  private final Logger log = LoggerFactory.getLogger(UserSession.class);

  private CdnRepositoryHandler handler;
  private RepositoryClient repositoryClient;
  private KurentoClient kurentoClient;
  private MediaPipeline mediaPipeline;
  private WebRtcEndpoint webRtcEndpoint;
  private RecorderEndpoint recorderEndpoint;
  private PlayerEndpoint playerEndpoint;
  private RepositoryItemRecorder repositoryItemRecorder;
  private String sessionId;
  private long stopTimestamp;

  public UserSession(String sessionId, RepositoryClient repositoryClient,
      CdnRepositoryHandler handler) {
    this.sessionId = sessionId;
    this.repositoryClient = repositoryClient;
    this.handler = handler;
  }

  public String startRecording(WebSocketSession session, String sdpOffer) {
    // KurentoClient
    kurentoClient = KurentoClient.create();
    log.info("Created kurentoClient (session {})", sessionId);

    // Media pipeline
    mediaPipeline = kurentoClient.createMediaPipeline();
    log.info("Created Media Pipeline for recording {} (session {})", mediaPipeline.getId(),
        sessionId);

    // Repository item (recorder)
    try {
      Map<String, String> metadata = Collections.emptyMap();
      repositoryItemRecorder = repositoryClient.createRepositoryItem(metadata);

    } catch (Exception e) {
      log.warn("Exception creating repositoryItemRecorder", e);

      // This code allows to run the demo in local without a repository server
      repositoryItemRecorder = new RepositoryItemRecorder();
      String id = String.valueOf(System.currentTimeMillis());
      repositoryItemRecorder.setId(id);
      repositoryItemRecorder.setUrl("file:///tmp/" + id + ".webm");
    }

    log.info("Repository item id={}, url={}", repositoryItemRecorder.getId(),
        repositoryItemRecorder.getUrl());

    // Media elements and connectivity
    webRtcEndpoint = new WebRtcEndpoint.Builder(mediaPipeline).build();
    recorderEndpoint = new RecorderEndpoint.Builder(mediaPipeline, repositoryItemRecorder.getUrl())
        .withMediaProfile(MediaProfileSpecType.WEBM).build();
    webRtcEndpoint.connect(webRtcEndpoint);
    webRtcEndpoint.connect(recorderEndpoint);

    // WebRTC negotiation
    String sdpAnswer = performWebRtcNegotiation(session, sdpOffer);

    // Start recording
    recorderEndpoint.record();

    return sdpAnswer;
  }

  public String playRecording(final WebSocketSession session, String sdpOffer) {
    // KurentoClient
    kurentoClient = KurentoClient.create();
    log.info("Created kurentoClient (session {})", sessionId);

    // Media pipeline
    mediaPipeline = kurentoClient.createMediaPipeline();
    log.info("Created Media Pipeline for playing {} (session {})", mediaPipeline.getId(),
        sessionId);

    // Repository item (player)
    RepositoryItemPlayer repositoryItemPlayer = null;
    try {

      // Guard time for repository to finish storing media
      long diff = System.currentTimeMillis() - stopTimestamp;
      if (diff >= 0 && diff < REPOSITORY_GUARD_TIME_MS) {
        log.info(
            "Waiting for {}ms before requesting the repository read endpoint "
                + "(requires {}ms before upload is considered terminated "
                + "and only {}ms have passed)",
            REPOSITORY_GUARD_TIME_MS - diff, REPOSITORY_GUARD_TIME_MS, diff);
        Thread.sleep(REPOSITORY_GUARD_TIME_MS - diff);
      }
      repositoryItemPlayer = repositoryClient.getReadEndpoint(repositoryItemRecorder.getId());

    } catch (Exception e) {
      log.warn("Exception creating repositoryItemPlayer", e);

      // This code allows to run the demo in local without a repository server
      repositoryItemPlayer = new RepositoryItemPlayer();
      repositoryItemPlayer.setId(repositoryItemRecorder.getId());
      repositoryItemPlayer.setUrl(repositoryItemRecorder.getUrl());
    }

    // Media elements and connectivity
    webRtcEndpoint = new WebRtcEndpoint.Builder(mediaPipeline).build();
    playerEndpoint = new PlayerEndpoint.Builder(mediaPipeline, repositoryItemPlayer.getUrl())
        .build();
    playerEndpoint.connect(webRtcEndpoint);

    playerEndpoint.addErrorListener(new EventListener<ErrorEvent>() {
      @Override
      public void onEvent(ErrorEvent event) {
        log.info("ErrorEvent for session {}: {}", session.getId(), event.getDescription());

        handler.sendPlayEnd(session);
        release();
      }
    });
    playerEndpoint.addEndOfStreamListener(new EventListener<EndOfStreamEvent>() {
      @Override
      public void onEvent(EndOfStreamEvent event) {
        log.info("EndOfStreamEvent for session {}", session.getId());

        handler.sendPlayEnd(session);
        release();
      }
    });

    // WebRTC negotiation
    String sdpAnswer = performWebRtcNegotiation(session, sdpOffer);

    // Start playing
    playerEndpoint.play();

    return sdpAnswer;
  }
  
  public String getRepositoryItemURL(){
	    
	  if(repositoryItemRecorder != null)		  
	  {
		  RepositoryItemPlayer itemPlayer = repositoryClient.getReadEndpoint(repositoryItemRecorder.getId());
		  return itemPlayer.getUrl();		
	  }
	  else
		  return null;
	  }

  public String performWebRtcNegotiation(final WebSocketSession session, String sdpOffer) {
    log.info("Starting WebRTC negotiation in session {}", sessionId);

    // Subscribe to ICE candidates
    webRtcEndpoint.addOnIceCandidateListener(new EventListener<OnIceCandidateEvent>() {
      @Override
      public void onEvent(OnIceCandidateEvent event) {
        JsonObject response = new JsonObject();
        response.addProperty("id", "iceCandidate");
        response.add("candidate", JsonUtils.toJsonObject(event.getCandidate()));
        handler.sendMessage(session, new TextMessage(response.toString()));
      }
    });

    // SDP negotiation
    String sdpAnswer = webRtcEndpoint.processOffer(sdpOffer);

    // Gather ICE candidates
    webRtcEndpoint.gatherCandidates();

    return sdpAnswer;
  }

  public void stopRecording() {
    recorderEndpoint.stop();
    stopTimestamp = System.currentTimeMillis();
    release();
  }

  public void addCandidate(JsonObject jsonCandidate) {
    IceCandidate candidate = new IceCandidate(jsonCandidate.get("candidate").getAsString(),
        jsonCandidate.get("sdpMid").getAsString(), jsonCandidate.get("sdpMLineIndex").getAsInt());
    webRtcEndpoint.addIceCandidate(candidate);
  }

  public void stopPlay() {
    playerEndpoint.stop();
    release();
  }

  public void release() {
    log.info("Releasing media pipeline {} (session {})", mediaPipeline.getId(), sessionId);
    mediaPipeline.release();

    log.info("Destroying kurentoClient (session {})", sessionId);
    kurentoClient.destroy();
  }

}
