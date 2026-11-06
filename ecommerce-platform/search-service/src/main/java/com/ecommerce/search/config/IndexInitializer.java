package com.ecommerce.search.config;

import com.ecommerce.search.document.ProductDocument;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.elasticsearch.core.ElasticsearchOperations;
import org.springframework.data.elasticsearch.core.IndexOperations;

/**
 * Creates the products index with the mapping the annotations describe.
 *
 * <h2>Why this class has to exist, and the failure it fixes</h2>
 *
 * <p><strong>Annotations describe an index; they do not create one.</strong> Spring Data
 * Elasticsearch applies {@code @Document}, {@code @Setting} and {@code @MultiField} when *it* creates
 * the index. Call {@code ElasticsearchOperations.save()} against an index that does not exist and
 * <em>Elasticsearch</em> creates it first, by dynamic mapping, from the shape of the first document —
 * and every annotation is silently ignored.
 *
 * <p>That is exactly what happened here, and the way it presented is worth recording because nothing
 * failed:
 *
 * <pre>
 *   32,951 documents indexed          fine
 *   GET /api/search?q=computr         30 hits - fuzzy matching worked
 *   GET /api/search?q=health beauty   0 hits
 *   GET _analyze?analyzer=product_analyzer  ->  "failed to find analyzer [product_analyzer]"
 *   GET /products/_settings           ->  analysis: {}
 * </pre>
 *
 * <p>Single-word queries worked because the default analyser handles them, so the service looked
 * healthy. It was the two-word category — the one thing the custom analyser exists for — that
 * exposed it. A search index that is 80% working is the hardest kind of broken to notice.
 *
 * <h2>Why `health_beauty` needs a char filter at all</h2>
 *
 * <p>The standard tokenizer follows Unicode text segmentation, and <strong>underscore is not a word
 * boundary</strong> — {@code health_beauty} is one token. So a user typing "health beauty" produces
 * two tokens that match neither. The {@code underscore_to_space} char filter rewrites the input
 * before tokenisation, which is the only place that can fix it: a token filter runs too late, on a
 * token that has already been formed wrongly.
 *
 * <p>All 71 Olist categories are underscore-joined, so without this the entire category vocabulary is
 * searchable only by typing the underscore.
 *
 * <h2>Mappings cannot be changed in place</h2>
 *
 * <p>Elasticsearch will add a new field to a mapping and will not change an existing one — analysers
 * included. So a mapping change means a new index and a reindex; there is no ALTER. This runner
 * therefore only ever *creates*, and refuses to touch an index that already exists, because silently
 * deleting 33K documents at startup is not a thing a service should do to itself.
 *
 * <p>{@code app.search.recreate-index-on-start} exists for development, defaults to false, and says
 * what it does in its name.
 */
@Configuration
@RequiredArgsConstructor
@Slf4j
public class IndexInitializer {

    private final ElasticsearchOperations elasticsearch;

    @Value("${app.search.recreate-index-on-start:false}")
    private boolean recreate;

    @Bean
    public ApplicationRunner createProductIndex() {
        return args -> {
            IndexOperations index = elasticsearch.indexOps(ProductDocument.class);

            if (index.exists() && recreate) {
                log.warn("app.search.recreate-index-on-start=true - DELETING the products index");
                index.delete();
            }

            if (index.exists()) {
                // Loud, because this is the state the original bug was in: an index that exists with
                // the wrong mapping behaves like a working one until a specific query proves it is
                // not. Checking for the analyser by name is the cheapest thing that would have
                // caught it.
                boolean analysed = index.getSettings().toString().contains("product_analyzer");
                if (analysed) {
                    log.info("products index present with the expected analyser");
                } else {
                    log.error("The products index EXISTS BUT WAS NOT CREATED FROM THIS MAPPING - "
                            + "no `product_analyzer`, so multi-word category queries will silently "
                            + "return nothing. Elasticsearch cannot change an analyser in place: "
                            + "delete the index, restart, and re-run POST /api/products/reindex.");
                }
                return;
            }

            // createWithMapping(), not create(): create() makes an empty index and leaves the
            // mapping to dynamic inference, which is the whole bug this class exists to prevent.
            index.createWithMapping();
            log.info("Created the products index with its analyser and mapping");
        };
    }
}
