/*
 * JBoss, Home of Professional Open Source
 * Copyright 2010, Red Hat, Inc. and individual contributors by the
 * @authors tag. See the copyright.txt in the distribution for a
 * full listing of individual contributors.
 *
 * This is free software; you can redistribute it and/or modify it
 * under the terms of the GNU Lesser General Public License as
 * published by the Free Software Foundation; either version 2.1 of
 * the License, or (at your option) any later version.
 *
 * This software is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU
 * Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public
 * License along with this software; if not, write to the Free
 * Software Foundation, Inc., 51 Franklin St, Fifth Floor, Boston, MA
 * 02110-1301 USA, or see the FSF site: http://www.fsf.org.
 */
package org.jdiameter.client.impl.router;

import static org.jdiameter.client.impl.helpers.Parameters.AcctApplId;
import static org.jdiameter.client.impl.helpers.Parameters.ApplicationId;
import static org.jdiameter.client.impl.helpers.Parameters.AuthApplId;
import static org.jdiameter.client.impl.helpers.Parameters.OwnRealm;
import static org.jdiameter.client.impl.helpers.Parameters.RealmEntry;
import static org.jdiameter.client.impl.helpers.Parameters.RealmTable;
import static org.jdiameter.client.impl.helpers.Parameters.VendorId;
import static org.jdiameter.server.impl.helpers.Parameters.RealmEntryExpTime;
import static org.jdiameter.server.impl.helpers.Parameters.RealmEntryIsDynamic;
import static org.jdiameter.server.impl.helpers.Parameters.RealmHosts;
import static org.jdiameter.server.impl.helpers.Parameters.RealmLocalAction;
import static org.jdiameter.server.impl.helpers.Parameters.RealmName;

import java.io.IOException;
import java.net.URISyntaxException;
import java.net.UnknownServiceException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.locks.ReadWriteLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;

import org.jdiameter.api.ApplicationId;
import org.jdiameter.api.Avp;
import org.jdiameter.api.AvpDataException;
import org.jdiameter.api.AvpSet;
import org.jdiameter.api.Configuration;
import org.jdiameter.api.IllegalDiameterStateException;
import org.jdiameter.api.InternalException;
import org.jdiameter.api.LocalAction;
import org.jdiameter.api.MetaData;
import org.jdiameter.api.RouteException;
import org.jdiameter.api.URI;
import org.jdiameter.client.api.IAnswer;
import org.jdiameter.client.api.IMessage;
import org.jdiameter.client.api.IRequest;
import org.jdiameter.client.api.controller.IPeer;
import org.jdiameter.client.api.controller.IPeerTable;
import org.jdiameter.client.api.controller.IRealm;
import org.jdiameter.client.api.controller.IRealmTable;
import org.jdiameter.client.api.router.IRouter;
import org.jdiameter.client.impl.helpers.Parameters;
import org.jdiameter.common.api.concurrent.IConcurrentFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Diameter Routing Core
 * 
 * @author <a href="mailto:brainslog@gmail.com"> Alexandre Mendonca </a>
 * @author <a href="mailto:baranowb@gmail.com"> Bartosz Baranowski </a>
 */
public class RouterImpl implements IRouter {

  public static final int DONT_CACHE = 0;
  public static final int ALL_SESSION = 1;
  public static final int ALL_REALM = 2;
  public static final int REALM_AND_APPLICATION = 3;
  public static final int ALL_APPLICATION = 4;
  public static final int ALL_HOST = 5;
  public static final int ALL_USER = 6;
  //
  private static final Logger logger = LoggerFactory.getLogger(RouterImpl.class);
  protected MetaData metaData;
  //
  //private ConcurrentHashMap<String, String[]> network = new ConcurrentHashMap<String, String[]>();
  protected IRealmTable realmTable;
  // Redirection feature
  public final int REDIRECT_TABLE_SIZE = 1024;
  //TODO: index it differently.
  protected List<RedirectEntry> redirectTable = new ArrayList<RedirectEntry>(REDIRECT_TABLE_SIZE);
  protected IConcurrentFactory concurrentFactory;


  // Answer routing feature
  public static final int REQUEST_TABLE_SIZE = 10 * 1024;
  public static final int REQUEST_TABLE_CLEAR_SIZE = 5 * 1024;
  protected ReadWriteLock requestEntryTableLock = new ReentrantReadWriteLock();
  protected ReadWriteLock redirectTableLock = new ReentrantReadWriteLock();
  protected Map<Long, AnswerEntry> requestEntryTable = new HashMap<Long, AnswerEntry>(REQUEST_TABLE_SIZE);
  protected List<Long> requestSortedEntryTable = new ArrayList<Long>();
  protected boolean isStopped = true;

  public RouterImpl(IConcurrentFactory concurrentFactory, IRealmTable realmTable,Configuration config, MetaData aMetaData) {
    this.concurrentFactory = concurrentFactory;
    this.metaData = aMetaData;
    this.realmTable = realmTable;
    logger.debug("Constructor for RouterImpl: Calling loadConfiguration");
    loadConfiguration(config);
  }

  protected void loadConfiguration(Configuration config) {
    logger.debug("Loading Router Configuration. Populating Realms, Application IDs, etc");
    //add local realm : this might not be good
    String localRealm = config.getStringValue(OwnRealm.ordinal(),null);
    String localHost = config.getStringValue(Parameters.OwnDiameterURI.ordinal(),null);
    try {
      this.realmTable.addLocalRealm(localRealm, new URI(localHost).getFQDN());
    }
    catch (UnknownServiceException use) {
      throw new RuntimeException("Unable to create URI from Own URI config value:" + localHost, use);
    }
    catch (URISyntaxException use) {
      throw new RuntimeException("Unable to create URI from Own URI config value:" + localHost, use);
    }

    //add realms based on realm table.
    if (config.getChildren(RealmTable.ordinal()) != null) {
      logger.debug("Going to loop through configured realms and add them into a network map");
      for (Configuration items : config.getChildren(RealmTable.ordinal())) {
        if (items != null) {
          Configuration[] m = items.getChildren(RealmEntry.ordinal());
          for (Configuration c : m) {
            try {
              String name = c.getStringValue(RealmName.ordinal(), "");
              logger.debug("Getting config for realm [{}]", name);
              ApplicationId appId = null;
              {
                Configuration[] apps = c.getChildren(ApplicationId.ordinal());
                if (apps != null) {
                  for (Configuration a : apps) {
                    if (a != null) {
                      long vnd = a.getLongValue(VendorId.ordinal(), 0);
                      long auth = a.getLongValue(AuthApplId.ordinal(), 0);
                      long acc = a.getLongValue(AcctApplId.ordinal(), 0);
                      if (auth != 0) {
                        appId = org.jdiameter.api.ApplicationId.createByAuthAppId(vnd, auth);
                      }
                      else {
                        appId = org.jdiameter.api.ApplicationId.createByAccAppId(vnd, acc);
                      }
                      if (logger.isDebugEnabled()) {
                        logger.debug("Realm [{}] has application Acct [{}] Auth [{}] Vendor [{}]", new Object[]{name, appId.getAcctAppId(), appId.getAuthAppId(), appId.getVendorId()});
                      }
                      break;
                    }
                  }
                }
              }
              String[] hosts = c.getStringValue(RealmHosts.ordinal(), (String) RealmHosts.defValue()).split(",");
              logger.debug("Adding realm [{}] with hosts [{}] to network map", name, hosts);
              LocalAction locAction = LocalAction.valueOf(c.getStringValue(RealmLocalAction.ordinal(), "0"));
              boolean isDynamic = c.getBooleanValue(RealmEntryIsDynamic.ordinal(), false);
              long expirationTime = c.getLongValue(RealmEntryExpTime.ordinal(), 0);
              this.realmTable.addRealm(name, appId, locAction, isDynamic, expirationTime, hosts);
            }
            catch (Exception e) {
              logger.warn("Unable to append realm entry", e);
            }
          }
        }
      }
    }
  }

  public void registerRequestRouteInfo(IRequest request) {
    logger.debug("Entering registerRequestRouteInfo");
    try {
      long hopByHopId = request.getHopByHopIdentifier();
      Avp hostAvp = request.getAvps().getAvp(Avp.ORIGIN_HOST);
      Avp realmAvp = request.getAvps().getAvp(Avp.ORIGIN_REALM);
      AnswerEntry entry = new AnswerEntry(hopByHopId, hostAvp != null ? hostAvp.getOctetString() : null,
          realmAvp != null ? realmAvp.getOctetString() : null);

      logger.debug("Adding Hop-by-Hop id [{}] into request entry table for routing answers back to the requesting peer", hopByHopId);
      requestEntryTable.put(hopByHopId, entry);
      requestSortedEntryTable.add(hopByHopId);

      if ( requestEntryTable.size() > REQUEST_TABLE_SIZE) {
        try{
          requestEntryTableLock.writeLock().lock();
          List<Long> toRemove = requestSortedEntryTable.subList(0, REQUEST_TABLE_CLEAR_SIZE/4);
          // removing from keyset removes from hashmap too
          requestEntryTable.keySet().removeAll(toRemove);
          // instead of wasting time removing, just make a new one, much faster
          requestSortedEntryTable = new ArrayList<Long>(requestSortedEntryTable.subList(REQUEST_TABLE_CLEAR_SIZE, requestSortedEntryTable.size()));
          // help garbage collector
          toRemove = null;
          if (logger.isDebugEnabled()) {
            logger.debug("Request entry table has now [{}] entries.", requestEntryTable.size());
          }
        }
        finally {
          requestEntryTableLock.writeLock().unlock();
        }
      }
    }
    catch (Exception e) {
      logger.warn("Unable to store route info", e);
    }
  }

  public String[] getRequestRouteInfo(long hopByHopIdentifier) {
    requestEntryTableLock.readLock().lock();
    AnswerEntry ans = requestEntryTable.get(hopByHopIdentifier);
    requestEntryTableLock.readLock().unlock();
    if (ans != null) {
      if (logger.isDebugEnabled()) {
        logger.debug("getRequestRouteInfo found host [{}] and realm [{}] for Hop-by-Hop Id [{}]", new Object[]{ans.getHost(), ans.getRealm(), hopByHopIdentifier});
      }
      return new String[] {ans.getHost(), ans.getRealm()};
    }
    else {
      if(logger.isWarnEnabled()) {
        logger.warn("Could not find route info for Hop-by-Hop Id [{}]. Table size is [{}]", hopByHopIdentifier, requestEntryTable.size());
      }
      return null;
    }
  }

  public IPeer getPeer(IMessage message, IPeerTable manager) throws RouteException, AvpDataException {
    logger.debug("Getting a peer for message [{}]", message);
    //FIXME: add ability to send without matching realm+peer pair?, that is , route based on peer table entries?
    //that is, if msg.destHost != null > getPeer(msg.destHost).sendMessage(msg);
    String destRealm = null;
    String destHost = null;
    IRealm matchedRealm = null;
    String[] info = null;
    // Get destination information
    if(message.isRequest()) {
      Avp avpRealm = message.getAvps().getAvp(Avp.DESTINATION_REALM);
      if (avpRealm == null) {
        throw new RouteException("Destination realm avp is empty");
      }
      destRealm = avpRealm.getOctetString();

      Avp avpHost = message.getAvps().getAvp(Avp.DESTINATION_HOST);
      if (avpHost != null) {
        destHost = avpHost.getOctetString();
      }
      if(logger.isDebugEnabled()) {
        logger.debug("Looking up peer for request: [{}], DestHost=[{}], DestRealm=[{}]", new Object[] {message,destHost, destRealm});
      }

      matchedRealm = (IRealm) this.realmTable.matchRealm(message); 
    }
    else {
      //answer, search
      info = getRequestRouteInfo(message.getHopByHopIdentifier());
      if (info != null) {
        destHost = info[0];
        destRealm = info[1];
        logger.debug("Message is an answer. Host is [{}] and Realm is [{}] as per hopbyhop info from request", destHost, destRealm);
        if (destRealm == null) {
          logger.warn("Destination-Realm was null for hopbyhop id " + message.getHopByHopIdentifier());
        }
      }
      else {
        logger.debug("No Host and realm found based on hopbyhop id of the answer associated request");
      }
      //FIXME: if no info, should not send it ?
      //FIXME: add strict deff in route back table so stack does not have to lookup?
      if(logger.isDebugEnabled()) {
        logger.debug("Looking up peer for answer: [{}], DestHost=[{}], DestRealm=[{}]", new Object[] {message,destHost, destRealm});
      }
      matchedRealm = (IRealm) this.realmTable.matchRealm((IAnswer)message,destRealm);
    }

    //  IPeer peer = getPeerPredProcessing(message, destRealm, destHost);
    //
    //  if (peer != null) {
    //    logger.debug("Found during preprocessing...[{}]", peer);
    //    return peer;
    //  }

    // Check realm name
    //TODO: check only if it exists?
    if (matchedRealm == null) {
      throw new RouteException("Unknown realm name [" + destRealm + "]");
    }

    // THIS IS GET PEER, NOT ROUTE!!!!!!!
    // Redirect processing
    //redirectProcessing(message, destRealm, destHost);
    // Check previous context information, this takes care of most answers.
    if (message.getPeer() != null && destHost != null && destHost.equals(message.getPeer().getUri().getFQDN()) && message.getPeer().hasValidConnection()) {
      if(logger.isDebugEnabled()) {
        logger.debug("Select previous message usage peer [{}]", message.getPeer());
      }
      return message.getPeer();
    }

    // Balancing procedure

    IPeer c = (IPeer) (destHost != null ? manager.getPeer(destHost) : null);

    if (c != null && c.hasValidConnection()) {
      logger.debug("Found a peer using destination host avp [{}] peer is [{}] with a valid connection.", destHost, c);
      //here matchedRealm MAY
      return c;
    }
    else {
      logger.debug("Finding peer by destination host avp [host={}] did not find anything. Now going to try finding one by destination realm [{}]", destRealm, destHost);
      String peers[] = matchedRealm.getPeerNames();
      if (peers == null || peers.length == 0) {
        throw new RouteException("Unable to find context by route information [" + destRealm + " ," + destHost + "]");
      }

      // Collect peers
      ArrayList<IPeer> availablePeers = new ArrayList<IPeer>(5);
      logger.debug("Looping through peers in realm [{}]", destRealm);
      for (String peerName : peers) {
        IPeer localPeer = (IPeer) manager.getPeer(peerName);
        if(logger.isDebugEnabled()) {
          logger.debug("Checking peer with uri [{}]", localPeer.getUri().toString());
        }
        if (localPeer != null) {
          if(localPeer.hasValidConnection()) {
            if(logger.isDebugEnabled()) {
              logger.debug("Found available peer to add to available peer list with uri [{}] with a valid connection", localPeer.getUri().toString());
            }
            availablePeers.add(localPeer);
          }
          else {
            if(logger.isDebugEnabled()) {
              logger.debug("Found a peer with uri [{}] with no valid connection", localPeer.getUri());
            }
          }
        }
      }

      if(logger.isDebugEnabled()) {
        logger.debug("Performing Realm routing. Realm [{}] has the following peers available [{}] from list [{}]", new Object[] {destRealm, availablePeers, Arrays.asList(peers)});
      }

      // Balancing
      IPeer peer = selectPeer(availablePeers);
      if (peer == null) {
        throw new RouteException("Unable to find valid connection to peer[" + destHost + "] in realm[" + destRealm + "]");
      }
      else {
        if (logger.isDebugEnabled()) {
          logger.debug("Load balancing selected peer with uri [{}]", peer.getUri());
        }
      }

      return peer;
    }
  }

  public IRealmTable getRealmTable() {
    return this.realmTable;
  }

  public void processRedirectAnswer(IRequest request, IAnswer answer, IPeerTable table) throws InternalException, RouteException {
    try {
      Avp destinationRealmAvp = request.getAvps().getAvp(Avp.DESTINATION_REALM);
      if(destinationRealmAvp == null) {
        throw new RouteException("Request to be routed has no Destination-Realm AVP!"); // sanity check... if user messes with us
      }
      String destinationRealm = destinationRealmAvp.getDiameterIdentity();
      String[] redirectHosts = null;
      if (answer.getAvps().getAvps(Avp.REDIRECT_HOST) != null) {
        AvpSet avps = answer.getAvps().getAvps(Avp.REDIRECT_HOST);
        redirectHosts = new String[avps.size()];
        int i = 0;
        // loop detected
        for (Avp avp : avps) {
          String r =  avp.getOctetString();
          if (r.equals(metaData.getLocalPeer().getUri().getFQDN())) {
            throw new RouteException("Loop detected");
          }
          redirectHosts[i++] = r;
        }
      }
      //
      int redirectUsage = DONT_CACHE;
      Avp redirectHostUsageAvp = answer.getAvps().getAvp(Avp.REDIRECT_HOST_USAGE);
      if (redirectHostUsageAvp != null) {
        redirectUsage = redirectHostUsageAvp.getInteger32();
      }

      if (redirectUsage != DONT_CACHE) {
        long redirectCacheTime = 0;
        Avp redirectCacheMaxTimeAvp = answer.getAvps().getAvp(Avp.REDIRECT_MAX_CACHE_TIME);
        if (redirectCacheMaxTimeAvp != null) {
          redirectCacheTime = redirectCacheMaxTimeAvp.getUnsigned32();
        }
        String primaryKey = null;
        ApplicationId secondaryKey = null;
        switch (redirectUsage) {
          case ALL_SESSION:
            primaryKey = request.getSessionId();
            break;
          case ALL_REALM:
            primaryKey = destinationRealm;
            break;
          case REALM_AND_APPLICATION:
            primaryKey = destinationRealm;
            secondaryKey = ((IMessage)request).getSingleApplicationId();
            break;
          case ALL_APPLICATION:
            secondaryKey = ((IMessage)request).getSingleApplicationId();
            break;
          case ALL_HOST:
            Avp destinationHostAvp = ((IRequest)request).getAvps().getAvp(Avp.DESTINATION_HOST);
            if(destinationHostAvp == null) {
              throw new RouteException("Request to be routed has no Destination-Host AVP!"); // sanity check... if user messes with us
            }
            primaryKey = destinationHostAvp.getDiameterIdentity();
            break;
          case ALL_USER:
            Avp userNameAvp = answer.getAvps().getAvp(Avp.USER_NAME);
            if (userNameAvp == null) {
              throw new RouteException("Request to be routed has no User-Name AVP!"); // sanity check... if user messes with us
            }
            primaryKey = userNameAvp.getUTF8String();
            break;
        }
        //
        if(redirectTable.size()> REDIRECT_TABLE_SIZE) {
          try {
            //yes, possible that this will trigger this procedure twice, but thats worst than locking always.
            redirectTableLock.writeLock().lock();
            trimRedirectTable();
          }
          finally {
            redirectTableLock.writeLock().unlock();
          }
        }
        if (REDIRECT_TABLE_SIZE > redirectTable.size()) {
          RedirectEntry e = new RedirectEntry(primaryKey, secondaryKey, redirectCacheTime, redirectUsage, redirectHosts, destinationRealm);
          redirectTable.add(e);
          //redirectProcessing(answer,destRealm.getOctetString(),destHost !=null ? destHost.getOctetString():null);
          //we dont have to elect?
          updateRoute(request,e.getRedirectHost());
        }
        else {
          if (redirectHosts != null && redirectHosts.length > 0) {
            String destHost = redirectHosts[0];
            //setRouteInfo(answer, getRealmForPeer(destHost), destHost);
            updateRoute(request,destHost);
          }
        }
      }
      else {
        if (redirectHosts != null && redirectHosts.length > 0) {
          String destHost = redirectHosts[0];
          //setRouteInfo(answer, getRealmForPeer(destHost), destHost);
          updateRoute(request,destHost);
        }
      }
      //now send
      table.sendMessage((IMessage)request);
    }
    catch (AvpDataException exc) {
      throw new InternalException(exc);
    }
    catch (IllegalDiameterStateException e) {
      throw new InternalException(e);
    }
    catch (IOException e) {
      throw new InternalException(e);
    }
  }

  /**
   * 
   */
  private void trimRedirectTable() {
    for(int index = 0; index < redirectTable.size(); index++) {
      try{
        if(redirectTable.get(index).getExpiredTime() <= System.currentTimeMillis()) {
          redirectTable.remove(index);
          index--; //a trick :)
        }
      }
      catch(Exception e) {
        logger.debug("Error in redirect task cleanup.", e);
        break;
      }
    }
  }

  /**
   * @param request
   * @param destHost
   */
  private void updateRoute(IRequest request, String destHost) {
    // Realm does not change I think... :)
    request.getAvps().removeAvp(Avp.DESTINATION_HOST);
    request.getAvps().addAvp(Avp.DESTINATION_HOST, destHost, true, false,  true);
  }

  public boolean updateRoute(IRequest message) throws RouteException, AvpDataException {
    AvpSet set = message.getAvps();
    Avp destRealmAvp = set.getAvp(Avp.DESTINATION_REALM);
    Avp destHostAvp = set.getAvp(Avp.DESTINATION_HOST);

    if(destRealmAvp == null) {
      throw new RouteException("Request does not have Destination-Realm AVP!");
    }

    String destRealm = destRealmAvp.getDiameterIdentity();
    String destHost = destHostAvp != null ? destHostAvp.getDiameterIdentity() : null;

    boolean matchedEntry = false;
    String userName = null;
    // get Session id
    String sessionId = message.getSessionId();
    //
    Avp avpUserName = message.getAvps().getAvp(Avp.USER_NAME);
    // Get application id
    ApplicationId appId = ((IMessage)message).getSingleApplicationId();
    // User name
    if (avpUserName != null)
      userName = avpUserName.getUTF8String();
    // Processing table
    try{
      redirectTableLock.readLock().lock();

      for (int index = 0;index<redirectTable.size();index++) {
        RedirectEntry e = redirectTable.get(index);
        switch (e.getUsageType()) {
          case ALL_SESSION: // Usage type: ALL SESSION
            matchedEntry = sessionId != null && e.primaryKey != null & sessionId.equals(e.primaryKey);
            break;
          case ALL_REALM: // Usage type: ALL REALM
            matchedEntry = destRealm != null && e.primaryKey != null & destRealm.equals(e.primaryKey);
            break;
          case REALM_AND_APPLICATION: // Usage type: REALM AND APPLICATION
            matchedEntry = destRealm != null & appId != null & e.primaryKey != null & e.secondaryKey != null & destRealm.equals(e.primaryKey)
            & appId.equals(e.secondaryKey);
            break;
          case ALL_APPLICATION: // Usage type: ALL APPLICATION
            matchedEntry = appId != null & e.secondaryKey != null & appId.equals(e.secondaryKey);
            break;
          case ALL_HOST: // Usage type: ALL HOST
            matchedEntry = destHost != null & e.primaryKey != null & destHost.equals(e.primaryKey);
            break;
          case ALL_USER: // Usage type: ALL USER
            matchedEntry = userName != null & e.primaryKey != null & userName.equals(e.primaryKey);
            break;
        }
        // Update message redirect information
        if (matchedEntry) {
          String newDestHost = e.getRedirectHost();
          //String newDestRealm = getRealmForPeer(destHost);
          //setRouteInfo(message, destRealm, newDestHost);
          updateRoute(message, newDestHost);
          logger.debug("Redirect message from host={}; to new-host={}, realm={} ", new Object[] { destHost, newDestHost,destRealm});
          return true;
        }
      }
    }
    finally {
      redirectTableLock.readLock().unlock();
    }
    return false;
  }

  protected IPeer getPeerPredProcessing(IMessage message, String destRealm, String destHost) {
    return null;
  }

  public void start() {
    if (isStopped) {
      //redirectScheduler = concurrentFactory.getScheduledExecutorService(RedirectMessageTimer.name());
      //redirectEntryHandler = redirectScheduler.scheduleAtFixedRate(redirectTask, 1, 1, TimeUnit.SECONDS);
      isStopped = false;
    }
  }

  public void stop() {
    isStopped = true;
    // if (redirectEntryHandler != null) {
    //  redirectEntryHandler.cancel(true);
    //}
    if (redirectTable != null) {
      redirectTable.clear();
    }
    if (requestEntryTable != null) {
      requestEntryTable.clear();
    }
    if (requestSortedEntryTable != null) {
      requestSortedEntryTable.clear();
    }
    //if (redirectScheduler != null) {
    //  concurrentFactory.shutdownNow(redirectScheduler);
    //}
  }

  public void destroy() {
    try {
      if (!isStopped) {
        stop();        
      }
    }
    catch (Exception exc) {
      logger.error("Unable to stop router", exc);
    }

    //redirectEntryHandler = null;
    //redirectScheduler = null;
    redirectTable = null;
    requestEntryTable = null;
    requestEntryTable = null;
  }

  protected IPeer selectPeer(List<IPeer> availablePeers) {
    IPeer p = null;
    for (IPeer c : availablePeers) {
      if (p == null || c.getRating() >= p.getRating()) {
        p = c;
      }
    }
    return p;
  }

  //    protected void redirectProcessing(IMessage message, final String destRealm, final String destHost) throws AvpDataException {
  //        String userName = null;
  //        // get Session id
  //        String sessionId = message.getSessionId();
  //        //
  //        Avp avpUserName = message.getAvps().getAvp(Avp.USER_NAME);
  //        // Get application id
  //        ApplicationId appId = message.getSingleApplicationId();
  //        // User name
  //        if (avpUserName != null)
  //            userName = avpUserName.getUTF8String();
  //        // Processing table
  //        for (RedirectEntry e : redirectTable.values()) {
  //            boolean matchedEntry = false;
  //            switch (e.getUsageType()) {
  //                case ALL_SESSION: // Usage type: ALL SESSION
  //                	matchedEntry = sessionId != null && e.primaryKey != null &
  //                            sessionId.equals(e.primaryKey);
  //                    break;
  //                case ALL_REALM: // Usage type: ALL REALM
  //                	matchedEntry = destRealm != null && e.primaryKey != null &
  //                            destRealm.equals(e.primaryKey);
  //                    break;
  //                case REALM_AND_APPLICATION: // Usage type: REALM AND APPLICATION
  //                	matchedEntry = destRealm != null & appId != null & e.primaryKey != null & e.secondaryKey != null &
  //                            destRealm.equals(e.primaryKey) & appId.equals(e.secondaryKey);
  //                    break;
  //                case ALL_APPLICATION: // Usage type: ALL APPLICATION
  //                	matchedEntry = appId != null & e.secondaryKey != null &
  //                            appId.equals(e.secondaryKey);
  //                    break;
  //                case ALL_HOST: // Usage type: ALL HOST
  //                	matchedEntry = destHost != null & e.primaryKey != null &
  //                            destHost.equals(e.primaryKey);
  //                    break;
  //                case ALL_USER: // Usage type: ALL USER
  //                	matchedEntry = userName != null & e.primaryKey != null &
  //                            userName.equals(e.primaryKey);
  //                    break;
  //            }
  //            // Update message redirect information
  //            if (matchedEntry) {
  //              String newDestHost  = e.getRedirectHost();
  //              // FIXME: Alexandre: Should use newDestHost? 
  //              String newDestRealm = getRealmForPeer(destHost);
  //              setRouteInfo(message, destRealm, newDestHost);
  //              logger.debug("Redirect message from host={}; realm={} to new-host={}; new-realm={}",
  //              new Object[] {destHost, destRealm, newDestHost, newDestRealm});
  //              return;
  //            }
  //        }
  //    }
  //
  //    private void setRouteInfo(IMessage message, String destRealm, String destHost) {
  //        message.getAvps().removeAvp(Avp.DESTINATION_REALM);
  //        message.getAvps().removeAvp(Avp.DESTINATION_HOST);
  //        if (destRealm != null)
  //            message.getAvps().addAvp(Avp.DESTINATION_REALM, destRealm, true, false, true);
  //        if (destHost != null)
  //            message.getAvps().addAvp(Avp.DESTINATION_HOST, destHost, true, false,  true);
  //    }
  //does not make sense, there can be multple realms :/
  //    public String  getRealmForPeer(String destHost) {
  //        for (String key : getRealmsName()) {
  //            for (String h : getRealmPeers(key)) {
  //                if (h.trim().equals(destHost.trim()))
  //                    return key;
  //            }
  //        }
  //        return null;
  //    }

  protected class RedirectEntry {

    final long createTime = System.currentTimeMillis();

    String primaryKey;
    ApplicationId secondaryKey;
    long liveTime;
    int usageType;
    String[] hosts;
    String destinationRealm;
    public RedirectEntry(String key1, ApplicationId key2, long time, int usage, String[] aHosts, String destinationRealm) throws InternalError {
      // Check arguments
      if (key1 == null && key2 == null) {
        throw new InternalError("Incorrect redirection key.");
      }
      if (aHosts == null || aHosts.length == 0) {
        throw new InternalError("Incorrect redirection hosts.");
      }
      // Set values
      this.primaryKey = key1;
      this.secondaryKey = key2;
      this.liveTime = time * 1000;
      this.usageType = usage;
      this.hosts = aHosts;
      this.destinationRealm = destinationRealm;
    }

    public int getUsageType() {
      return usageType;
    }

    public String[] getRedirectHosts() {
      return hosts;
    }

    public String getRedirectHost() {
      return hosts[hosts.length - 1];
    }

    public long getExpiredTime() {
      return createTime + liveTime;
    }

    public int hashCode() {
      int result = (primaryKey != null ? primaryKey.hashCode() : 0);
      result = 31 * result + (secondaryKey != null ? secondaryKey.hashCode() : 0);
      result = 31 * result + (int) (liveTime ^ (liveTime >>> 32));
      result = 31 * result + usageType;
      result = 31 * result + (hosts != null ? hosts.hashCode() : 0);
      return result;
    }

    public boolean equals(Object other) {
      if (other == this) {
        return true;
      }

      if (other instanceof RedirectEntry) {
        RedirectEntry that = (RedirectEntry) other;
        return liveTime == that.liveTime && usageType == that.usageType &&
        Arrays.equals(hosts, that.hosts) && !(primaryKey != null ? !primaryKey.equals(that.primaryKey) : that.primaryKey != null) &&
        !(secondaryKey != null ? !secondaryKey.equals(that.secondaryKey) : that.secondaryKey != null);
      }
      else {
        return false;
      }
    }
  }

  protected class AnswerEntry {

    final long createTime = System.nanoTime();

    Long hopByHopId;
    String host, realm;

    public AnswerEntry(Long hopByHopId) {
      this.hopByHopId = hopByHopId;
    }

    public AnswerEntry(Long hopByHopId, String host, String realm) throws InternalError {
      this.hopByHopId = hopByHopId;
      this.host = host;
      this.realm = realm;
    }

    public long getCreateTime() {
      return createTime;
    }

    public Long getHopByHopId() {
      return hopByHopId;
    }

    public String getHost() {
      return host;
    }

    public String getRealm() {
      return realm;
    }

    public boolean equals(Object o) {
      if (this == o) {
        return true;
      }
      if (o == null || getClass() != o.getClass()) {
        return false;
      }
      AnswerEntry that = (AnswerEntry) o;
      return hopByHopId == that.hopByHopId;
    }

    public String toString() {
      return "AnswerEntry{" + "createTime=" + createTime + ", hopByHopId=" + hopByHopId + '}';
    }
  }
}
