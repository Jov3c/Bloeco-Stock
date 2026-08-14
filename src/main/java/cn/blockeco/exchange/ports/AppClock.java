package cn.blockeco.exchange.ports;
import java.time.Instant;
@FunctionalInterface public interface AppClock { Instant now(); }
