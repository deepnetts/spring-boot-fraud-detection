package com.example.frauddetection.features;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;

import org.junit.jupiter.api.Test;

import com.example.frauddetection.domain.MerchantCategory;
import com.example.frauddetection.domain.Transaction;

import deepnetts.tensor.Tensor1D;

class FeatureExtractorTest {

    @Test
    void extractsEngineeredFeaturesAndOneHotCategory() {
        var tx = new Transaction("t1", 300.0, 100.0, 0, MerchantCategory.ELECTRONICS,
                0.0, 0.0, 0.0, 5, 1, 0, true, false, false, true, true, false);

        float[] f = new FeatureExtractor().encodeToTensor(tx).getValues();

        assertEquals(FeatureExtractor.NUM_FEATURES, f.length);
        assertEquals(Math.log1p(300), f[index("log_amount")], 1e-5);
        assertEquals(Math.log1p(3), f[index("log_amount_to_customer_avg")], 1e-5);
        assertEquals(0, f[index("hour_sin")], 1e-6);
        assertEquals(1, f[index("hour_cos")], 1e-6);
        assertEquals(1, f[index("is_night")]);
        assertEquals(5, f[index("txn_count_24h")]);
        assertEquals(1, f[index("card_not_present")]);
        assertEquals(0, f[index("used_chip")]);
        assertEquals(1, f[index("category_electronics")]);
        assertEquals(0, f[index("category_grocery")]);
    }

    @Test
    void scalerStandardisesAndLeavesConstantColumnsFinite() {
        var scaler = FeatureScaler.fit(List.of(new Tensor1D(1f, 5f), new Tensor1D(3f, 5f)));

        float[] scaled = scaler.transform(new Tensor1D(3f, 5f)).getValues();

        assertEquals(1, scaled[0], 1e-6);
        assertEquals(0, scaled[1], 1e-6);
    }

    private static int index(String name) {
        return FeatureExtractor.FEATURE_NAMES.indexOf(name);
    }
}
