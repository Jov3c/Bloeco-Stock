package cn.blockeco.exchange.application;

import cn.blockeco.exchange.domain.bluechip.BluechipEvent;
import cn.blockeco.exchange.ports.AppClock;
import cn.blockeco.exchange.ports.BluechipRepository;
import cn.blockeco.exchange.ports.TransactionRunner;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Random;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Executor;

/** Creates clearly fictional market flavour and applies bounded, mean-reverting model moves. */
public final class MarketEventService {
    private static final List<String> COMPANY_TEMPLATES = List.of("晶核产线效率提升", "仓储机器人完成校准", "浮空港订单节奏调整", "工坊能源维护完成");
    private static final List<String> INDUSTRY_TEMPLATES = List.of("协会发布新的协作标准", "供应链调度进入新周期", "工坊联合采购节奏变化", "技术社群开放测试成功");
    private static final List<String> MARKET_TEMPLATES = List.of("交易所流动性潮汐变化", "服务器冒险季热度升温", "各行业订单节奏重新平衡", "市场观察员发布模拟指数报告");
    private final BluechipRepository repository; private final TransactionRunner transactions; private final Executor executor; private final AppClock clock; private final Random random;
    public MarketEventService(BluechipRepository repository, TransactionRunner transactions, Executor executor, AppClock clock, Random random) { this.repository=Objects.requireNonNull(repository);this.transactions=Objects.requireNonNull(transactions);this.executor=Objects.requireNonNull(executor);this.clock=Objects.requireNonNull(clock);this.random=Objects.requireNonNull(random); }
    public CompletionStage<List<BluechipEvent>> triggerDueEvents(){return CompletableFuture.supplyAsync(()->{ Instant now=clock.now(); var schedule=repository.marketSchedule().orElseThrow(); java.util.ArrayList<BluechipEvent> result=new java.util.ArrayList<>(); Instant nextCompany=schedule.nextCompanyEventAt(),nextMarket=schedule.nextMarketEventAt(); var companies=repository.all(); if(!now.isBefore(nextCompany)&&!companies.isEmpty()){var c=companies.get(random.nextInt(companies.size()));Duration duration=randomDuration();result.add(draft("COMPANY",c.listing().stockCode(),null,randomImpact(),now,duration));nextCompany=now.plus(randomDuration());} if(!now.isBefore(nextMarket)&&!companies.isEmpty()){var c=companies.get(random.nextInt(companies.size()));Duration duration=randomDuration();String scope=random.nextBoolean()?"INDUSTRY":"MARKET";result.add(draft(scope,null,"INDUSTRY".equals(scope)?c.industry():null,randomImpact(),now,duration));nextMarket=now.plus(randomDuration());} Instant savedCompany=nextCompany,savedMarket=nextMarket;transactions.inTransaction(connection->{repository.expireEvents(connection,now);for(var event:result)persistAndApply(connection,event);repository.saveMarketSchedule(connection,new BluechipRepository.MarketSchedule(savedCompany,savedMarket));return null;}); return List.copyOf(result);},executor);}
    public CompletionStage<BluechipEvent> triggerTestEvent(String code,int impactBps){return CompletableFuture.supplyAsync(()->persist(draft("COMPANY",code,null,impactBps,clock.now(),Duration.ofHours(12))),executor);}
    public CompletionStage<BluechipEvent> triggerTestIndustryEvent(String industry,int impactBps){return CompletableFuture.supplyAsync(()->persist(draft("INDUSTRY",null,industry,impactBps,clock.now(),Duration.ofDays(1))),executor);}
    public CompletionStage<BluechipEvent> triggerTestMarketEvent(int impactBps){return CompletableFuture.supplyAsync(()->persist(draft("MARKET",null,null,impactBps,clock.now(),Duration.ofDays(1))),executor);}
    public CompletionStage<Void> applyDecay(){return CompletableFuture.runAsync(()->transactions.inTransaction(c->{repository.expireEvents(c,clock.now());repository.decayModels(c);return null;}),executor);}
    public CompletionStage<Void> expireEvents(){return CompletableFuture.runAsync(()->transactions.inTransaction(c->{repository.expireEvents(c,clock.now());return null;}),executor);}
    private BluechipEvent draft(String scope,String code,String industry,int impact,Instant now,Duration duration){ var company="COMPANY".equals(scope)?repository.findByStockCode(code).orElseThrow(()->new IllegalArgumentException("unknown bluechip: "+code)):null; String target=company==null?("MARKET".equals(scope)?"大盘":industry):company.listing().stockCode(); String template=template(scope); return new BluechipEvent(UUID.randomUUID().toString(),scope,company==null?null:company.companyId().value().toString(),industry,"Bloeco-Stock 模拟快讯："+target+" "+template,"这是 Bloeco-Stock 世界中的随机模拟经营事件："+template+"；不代表现实新闻。",impact,impact/2,now,now.plus(duration),"ACTIVE"); }
    private BluechipEvent persist(BluechipEvent event){transactions.inTransaction(c->{repository.expireEvents(c,event.startsAt());persistAndApply(c,event);return null;});return event;}
    private void persistAndApply(java.sql.Connection connection,BluechipEvent event) throws java.sql.SQLException { repository.persistEvent(connection,event);repository.applyModelImpact(connection,event.scope(),event.companyId(),event.industry(),event.priceImpactBps()); }
    private Duration randomDuration(){return Duration.ofMinutes(5+random.nextInt(16));}
    private String template(String scope){List<String> options="COMPANY".equals(scope)?COMPANY_TEMPLATES:("INDUSTRY".equals(scope)?INDUSTRY_TEMPLATES:MARKET_TEMPLATES);return options.get(random.nextInt(options.size()));}
    private int randomImpact(){return (random.nextBoolean()?1:-1)*(25+random.nextInt(96));}
}
