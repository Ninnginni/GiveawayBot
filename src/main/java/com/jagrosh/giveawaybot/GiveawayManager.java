/*
 * Copyright 2022 John Grosh (john.a.grosh@gmail.com).
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.jagrosh.giveawaybot;

import com.jagrosh.giveawaybot.data.CachedUser;
import com.jagrosh.giveawaybot.data.Database;
import com.jagrosh.giveawaybot.data.Giveaway;
import com.jagrosh.giveawaybot.data.GuildSettings;
import com.jagrosh.giveawaybot.entities.FileUploader;
import com.jagrosh.giveawaybot.entities.LocalizedMessage;
import com.jagrosh.giveawaybot.entities.PremiumLevel;
import com.jagrosh.giveawaybot.util.GiveawayUtil;
import com.jagrosh.giveawaybot.util.OtherUtil;
import com.jagrosh.interactions.components.ActionRowComponent;
import com.jagrosh.interactions.components.ButtonComponent;
import com.jagrosh.interactions.components.PartialEmoji;
import com.jagrosh.interactions.entities.*;
import com.jagrosh.interactions.requests.RestClient;
import com.jagrosh.interactions.requests.RestClient.RestResponse;
import com.jagrosh.interactions.requests.Route;
import com.jagrosh.interactions.util.JsonUtil;
import java.awt.Color;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.*;
import org.json.JSONObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 *
 * @author John Grosh (john.a.grosh@gmail.com)
 */
public class GiveawayManager
{
    public final static String ENTER_BUTTON_ID = "enter-giveaway";
    private final static int MINIMUM_SECONDS = 10,
                             MAX_PRIZE_LENGTH = 250,
                             MAX_DESCR_LENGTH = 1000,
                             FAILURE_COOLDOWN_TIME = 30;
    
    private final Logger log = LoggerFactory.getLogger(GiveawayManager.class);
    private final ScheduledExecutorService schedule = Executors.newSingleThreadScheduledExecutor();
    private final ExecutorService pool = Executors.newCachedThreadPool();
    private final Map<Long,Instant> latestFailure = new HashMap<>();
    private final Database database;
    private final RestClient rest;
    private final FileUploader uploader;
    
    public GiveawayManager(Database database, RestClient rest, FileUploader uploader)
    {
        this.database = database;
        this.rest = rest;
        this.uploader = uploader;
    }
    
    public void start()
    {
        schedule.scheduleWithFixedDelay(() -> 
        {
            try
            {
                // end giveaways that have run out of time
                database.getGiveawaysEndingBefore(Instant.now())
                        .forEach(giveaway -> pool.submit(() -> endGiveaway(giveaway)));
            }
            catch(Exception ex)
            {
                log.error("Exception in ending giveaways: ", ex);
            }
        }, 0, 1, TimeUnit.SECONDS);
    }
    
    public boolean endGiveaway(Giveaway giveaway)
    {
        List<CachedUser> entries = database.getEntries(giveaway.getMessageId());
        database.removeGiveaway(giveaway.getMessageId());
        List<CachedUser> pool = new ArrayList<>(entries);
        List<CachedUser> winners = GiveawayUtil.selectWinners(pool, giveaway.getWinners());
        CachedUser host = database.getUser(giveaway.getUserId());
        try
        {
            JSONObject summary = createGiveawaySummary(giveaway, host, entries, winners);
            String url = uploader.uploadFile(summary.toString(), "giveaway_summary.json");
            String summaryKey = url == null ? null : url.replaceAll(".*/(\\d+/\\d+)/.*", "$1");
            RestResponse res = rest.request(Route.PATCH_MESSAGE.format(giveaway.getChannelId(), giveaway.getMessageId()), renderGiveaway(giveaway, entries.size(), winners, summaryKey).toJson()).get();
            RestResponse res2 = rest.request(Route.POST_MESSAGE.format(giveaway.getChannelId()), renderWinnerMessage(giveaway, winners).toJson()).get();
        }
        catch(ExecutionException | InterruptedException ex)
        {
            return false;
        }
        return true;
    }
    
    public void checkAvailability(GuildMember member, long channelId, long guildId, PremiumLevel level, WebLocale locale) throws GiveawayException
    {
        // apply cooldown when giveaway creation fails
        Instant latest = latestFailure.get(guildId);
        if(latest != null && latest.until(Instant.now(), ChronoUnit.SECONDS) < FAILURE_COOLDOWN_TIME)
            throw new GiveawayException(LocalizedMessage.ERROR_GIVEAWAY_COOLDOWN);
        
        // check if the maximum number of giveaways has been reached
        long currentGiveaways = level.perChannelMaxGiveaways ? database.countGiveawaysByChannel(channelId) : database.countGiveawaysByGuild(guildId);
        if(currentGiveaways >= level.maxGiveaways)
            throw new GiveawayException(LocalizedMessage.ERROR_MAXIMUM_GIVEAWAYS_GUILD, currentGiveaways, level.perChannelMaxGiveaways);
    }
    
    public Giveaway constructGiveaway(User user, String time, String winners, String prize, String description, PremiumLevel level, WebLocale locale) throws GiveawayException
    {
        // validate time
        int seconds = OtherUtil.parseTime(time);
        if(seconds <= 0)
            throw new GiveawayException(LocalizedMessage.ERROR_INVALID_TIME_FORMAT, time);
        if(seconds < MINIMUM_SECONDS)
            throw new GiveawayException(LocalizedMessage.ERROR_INVALID_TIME_MIN, seconds, MINIMUM_SECONDS);
        if(seconds > level.maxTime)
            throw new GiveawayException(LocalizedMessage.ERROR_INVALID_TIME_MAX, seconds, level.maxTime);
        
        // validate number of winners
        int wins = 0;
        try
        {
            wins = Integer.parseInt(winners);
        }
        catch(NumberFormatException ex)
        {
            throw new GiveawayException(LocalizedMessage.ERROR_INVALID_WINNERS_FORMAT, winners);
        }
        if(wins < 1 || wins > level.maxWinners)
            throw new GiveawayException(LocalizedMessage.ERROR_INVALID_WINNERS_MAX, wins, 1, level.maxWinners);
        
        // validate prize and description
        if(prize.length() > MAX_PRIZE_LENGTH)
            throw new GiveawayException(LocalizedMessage.ERROR_INVALID_PRIZE_LENGTH, prize, MAX_PRIZE_LENGTH);
        if(description != null && description.length() > MAX_DESCR_LENGTH)
            throw new GiveawayException(LocalizedMessage.ERROR_INVALID_PRIZE_LENGTH, prize, MAX_PRIZE_LENGTH);
        
        return new Giveaway(user.getIdLong(), Instant.now().plusSeconds(seconds), wins, prize, description);
    }
    
    public long sendGiveaway(Giveaway giveaway, long guildId, long channelId) throws GiveawayException
    {
        try
        {
            giveaway.setGuildId(guildId);
            giveaway.setChannelId(channelId);
            SentMessage sm = renderGiveaway(giveaway, 0);
            log.info("Attempting to create giveaway, json: " + sm.toJson());
            RestResponse res = rest.request(Route.POST_MESSAGE.format(channelId), sm.toJson()).get();
            log.info("Attempted to create giveaway, response: " + res.getStatus() + ", " + res.getBody());
            if(!res.isSuccess())
            {
                latestFailure.put(guildId, Instant.now());
                if(res.getErrorSpecific() == 50013)
                    throw new GiveawayException(LocalizedMessage.ERROR_BOT_PERMISSIONS);
                throw new GiveawayException(LocalizedMessage.ERROR_GENERIC_CREATION);
            }
            ReceivedMessage rm = new ReceivedMessage(res.getBody());
            giveaway.setMessageId(rm.getIdLong());
            database.createGiveaway(giveaway);
            return giveaway.getMessageId();
        }
        catch(InterruptedException | ExecutionException ex)
        {
            latestFailure.put(guildId, Instant.now());
            throw new GiveawayException(LocalizedMessage.ERROR_GENERIC_CREATION);
        }
    }
    
    public SentMessage renderGiveaway(Giveaway giveaway, int numEntries)
    {
        return renderGiveaway(giveaway, numEntries, null, null);
    }
    
    public SentMessage renderGiveaway(Giveaway giveaway, int numEntries, List<CachedUser> winners, String summaryKey)
    {
        GuildSettings gs = database.getSettings(giveaway.getGuildId());
        String message = (giveaway.getDescription() == null || giveaway.getDescription().isEmpty() ? "" : giveaway.getDescription() + "\n\n")
                + (winners == null ? "Ends" : "Ended") + ": <t:" + giveaway.getEndInstant().getEpochSecond() + ":R> (<t:" + giveaway.getEndInstant().getEpochSecond() + ":f>)"
                + "\nHosted by: <@" + giveaway.getUserId() + ">"
                + "\nEntries: **" + numEntries + "**"
                + "\nWinners: " + (winners == null ? "**" + giveaway.getWinners() + "**" : renderWinners(winners));
        SentMessage.Builder sb = new SentMessage.Builder()
                .addEmbed(new Embed.Builder()
                        .setTitle(giveaway.getPrize(), null)
                        .setColor(winners == null ? gs.getColor() : new Color(0x2F3136))
                        .setTimestamp(giveaway.getEndInstant())
                        .setDescription(message).build());
        if(winners == null)
            sb.addComponent(new ActionRowComponent(new ButtonComponent(ButtonComponent.Style.PRIMARY, new PartialEmoji(gs.getEmoji(), 0L, false), ENTER_BUTTON_ID)));
        else if(summaryKey != null)
            sb.addComponent(new ActionRowComponent(new ButtonComponent("Giveaway Summary", Constants.SUMMARY + "#giveaway=" + summaryKey)));
        else
            sb.removeComponents();
        return sb.build();
    }
    
    public SentMessage renderWinnerMessage(Giveaway giveaway, List<CachedUser> winners)
    {
        return new SentMessage.Builder()
                .setContent(winners.isEmpty() 
                        ? LocalizedMessage.WARNING_NO_ENTRIES.getLocalizedMessage(WebLocale.ENGLISH_US) 
                        : LocalizedMessage.SUCCESS_WINNER.getLocalizedMessage(WebLocale.ENGLISH_US, renderWinners(winners), giveaway.getPrize()))
                .setReferenceMessage(giveaway.getMessageId())
                .setAllowedMentions(new AllowedMentions(AllowedMentions.ParseType.USERS))
                .build();
    }
    
    public String renderWinners(List<CachedUser> winners)
    {
        if(winners.isEmpty())
            return "";
        StringBuilder sb = new StringBuilder();
        for(CachedUser u: winners)
            sb.append(", <@").append(u.getId()).append(">");
        return sb.toString().substring(2);
    }
    
    private JSONObject createGiveawaySummary(Giveaway giveaway, CachedUser host, List<CachedUser> entries, List<CachedUser> winners)
    {
        return new JSONObject()
                .put("giveaway", new JSONObject()
                    .put("id", Long.toString(giveaway.getMessageId()))
                    .put("prize", giveaway.getPrize())
                    .put("num_winners", giveaway.getWinners())
                    .put("host", host.toJson())
                    .put("end", giveaway.getEndTime()))
                .put("winners", JsonUtil.buildArray(winners))
                .put("entries", JsonUtil.buildArray(entries));
    }
}