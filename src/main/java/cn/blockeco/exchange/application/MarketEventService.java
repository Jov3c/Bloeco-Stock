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
    private final BluechipRepository repository; private final TransactionRunner transactions; private final Executor executor; private final AppClock clock; private final Random random;
    public MarketEventService(BluechipRepository repository, TransactionRunner transactions, Executor executor, AppClock clock, Random random) { this.repository=Objects.requireNonNull(repository);this.transactions=Objects.requireNonNull(transactions);this.executor=Objects.requireNonNull(executor);this.clock=Objects.requireNonNull(clock);this.random=Objects.requireNonNull(random); }
    public CompletionStage<List<BluechipEvent>> triggerDueEvents(){return CompletableFuture.supplyAsync(()->{ Instant now=clock.now(); var schedule=repository.marketSchedule().orElseThrow(); java.util.ArrayList<BluechipEvent> result=new java.util.ArrayList<>(); Instant nextCompany=schedule.nextCompanyEventAt(),nextMarket=schedule.nextMarketEventAt(); var companies=repository.all(); if(!now.isBefore(nextCompany)&&!companies.isEmpty()){var c=companies.get(random.nextInt(companies.size()));result.add(create("COMPANY",c.listing().stockCode(),null,randomImpact(),now,Duration.ofHours(12)));nextCompany=now.plus(Duration.ofHours(6+random.nextInt(7)));} if(!now.isBefore(nextMarket)&&!companies.isEmpty()){var c=companies.get(random.nextInt(companies.size()));Duration duration=Duration.ofDays(1+random.nextInt(3));result.add(create("INDUSTRY",null,c.industry(),randomImpact(),now,duration));nextMarket=now.plus(duration);} Instant savedCompany=nextCompany,savedMarket=nextMarket;transactions.inTransaction(connection->{repository.saveMarketSchedule(connection,new BluechipRepository.MarketSchedule(savedCompany,savedMarket));return null;}); return List.copyOf(result);},executor);}
    public CompletionStage<BluechipEvent> triggerTestEvent(String code,int impactBps){return CompletableFuture.supplyAsync(()->create("COMPANY",code,null,impactBps,clock.now(),Duration.ofHours(12)),executor);}
    public CompletionStage<BluechipEvent> triggerTestIndustryEvent(String industry,int impactBps){return CompletableFuture.supplyAsync(()->create("INDUSTRY",null,industry,impactBps,clock.now(),Duration.ofDays(1)),executor);}
    public CompletionStage<Void> applyDecay(){return CompletableFuture.runAsync(()->transactions.inTransaction(c->{repository.decayModels(c);return null;}),executor);}
    private BluechipEvent create(String scope,String code,String industry,int impact,Instant now,Duration duration){ var company="COMPANY".equals(scope)?repository.findByStockCode(code).orElseThrow(()->new IllegalArgumentException("unknown bluechip: "+code)):null; String target=company==null?industry:company.listing().stockCode(); BluechipEvent event=new BluechipEvent(UUID.randomUUID().toString(),scope,company==null?null:company.companyId().value().toString(),industry,"BlockStock 模拟快讯："+target+" 物流挑战","这是 BlockStock 世界中的模拟经营事件，不代表现实新闻。",impact,impact/2,now,now.plus(duration),"ACTIVE"); transactions.inTransaction(c->{repository.persistEvent(c,event);repository.applyModelImpact(c,scope,event.companyId(),industry,impact);return null;}); return event; }
    private int randomImpact(){return (random.nextBoolean()?1:-1)*(200+random.nextInt(401));}
}
