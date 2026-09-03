package com.dst.v2xagent.analysis;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 基线 / 相关性分析器单元测试（纯函数）
 */
class AnalysisEngineTest {

    @Test
    void pctChangeAndAnomaly() {
        assertEquals(50.0, BaselineAnalyzer.pctChange(150.0, 100.0), 0.001);
        assertNull(BaselineAnalyzer.pctChange(100.0, 0.0), "基线为 0 不造数");
        assertNull(BaselineAnalyzer.pctChange(null, 100.0));
        assertTrue(BaselineAnalyzer.isAnomaly(35.0, 30.0));
        assertFalse(BaselineAnalyzer.isAnomaly(10.0, 30.0));
        assertFalse(BaselineAnalyzer.isAnomaly(null, 30.0));
    }

    @Test
    void meanAndZScore() {
        assertNull(BaselineAnalyzer.mean(List.of()));
        assertEquals(2.0, BaselineAnalyzer.mean(List.of(1.0, 2.0, 3.0)), 0.001);
        assertTrue(BaselineAnalyzer.isAnomalyByZScore(100.0,
                List.of(10.0, 11.0, 9.0, 10.0, 10.0, 11.0, 9.0)));
        assertFalse(BaselineAnalyzer.isAnomalyByZScore(100.0, List.of(10.0, 11.0)));
    }

    @Test
    void pearsonCorrelation() {
        Double r = CorrelationAnalyzer.pearson(
                List.of(1.0, 2.0, 3.0, 4.0, 5.0), List.of(2.0, 4.0, 6.0, 8.0, 10.0));
        assertNotNull(r);
        assertEquals(1.0, r, 0.001);
        assertEquals("strong", CorrelationAnalyzer.strength(r));
        Double neg = CorrelationAnalyzer.pearson(
                List.of(1.0, 2.0, 3.0, 4.0, 5.0), List.of(10.0, 8.0, 6.0, 4.0, 2.0));
        assertEquals(-1.0, neg, 0.001);
        assertNull(CorrelationAnalyzer.pearson(List.of(1.0, 2.0), List.of(1.0, 2.0)));
        assertNull(CorrelationAnalyzer.pearson(
                List.of(5.0, 5.0, 5.0), List.of(1.0, 2.0, 3.0)));
        assertEquals("none", CorrelationAnalyzer.strength(0.1));
    }
}
