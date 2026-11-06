"""The nine Olist CSVs, described once.

Both the loader and the synthetic generator import this module, which is the point: if the two had
their own idea of the column order, a generated file would load and a real one would not, and the
difference would only appear on the day the real data arrived.

Source: "Brazilian E-Commerce Public Dataset by Olist", Kaggle
(https://www.kaggle.com/datasets/olistbr/brazilian-ecommerce), CC BY-NC-SA 4.0.
The column names below are the dataset's own, misspellings included: `product_name_lenght` and
`product_description_lenght` are spelled that way in the published files. They are NOT corrected
here, because a loader that quietly renames a column is a loader that fails silently against the real
download.
"""

from __future__ import annotations

from dataclasses import dataclass


@dataclass(frozen=True)
class CsvSpec:
    """One source file: its name, its columns, and how many rows to expect."""

    filename: str
    columns: tuple[str, ...]
    #: Row count in the published dataset, used only to warn when a file looks wrong.
    #: Documented by the dataset, NOT measured by this project - see docs/data-setup.md.
    published_rows: int
    key: str


CUSTOMERS = CsvSpec(
    "olist_customers_dataset.csv",
    (
        "customer_id",
        # The dataset's own subtlety, and it matters for the warehouse: `customer_id` is unique per
        # ORDER, while `customer_unique_id` identifies the actual person across orders. Joining
        # dim_customer on the wrong one turns 96,096 real customers into 99,441 fake ones and makes
        # every "repeat purchase rate" answer zero.
        "customer_unique_id",
        "customer_zip_code_prefix",
        "customer_city",
        "customer_state",
    ),
    99_441,
    "customer_id",
)

GEOLOCATION = CsvSpec(
    "olist_geolocation_dataset.csv",
    (
        "geolocation_zip_code_prefix",
        "geolocation_lat",
        "geolocation_lng",
        "geolocation_city",
        "geolocation_state",
    ),
    1_000_163,
    "geolocation_zip_code_prefix",
)

ORDERS = CsvSpec(
    "olist_orders_dataset.csv",
    (
        "order_id",
        "customer_id",
        "order_status",
        "order_purchase_timestamp",
        "order_approved_at",
        "order_delivered_carrier_date",
        "order_delivered_customer_date",
        "order_estimated_delivery_date",
    ),
    99_441,
    "order_id",
)

ORDER_ITEMS = CsvSpec(
    "olist_order_items_dataset.csv",
    (
        "order_id",
        # 1-based within the order, so (order_id, order_item_id) is the natural key. There is no
        # quantity column: two of the same product on one order appear as two rows.
        "order_item_id",
        "product_id",
        "seller_id",
        "shipping_limit_date",
        "price",
        "freight_value",
    ),
    112_650,
    "order_id",
)

ORDER_PAYMENTS = CsvSpec(
    "olist_order_payments_dataset.csv",
    (
        "order_id",
        "payment_sequential",
        "payment_type",
        "payment_installments",
        "payment_value",
    ),
    103_886,
    "order_id",
)

ORDER_REVIEWS = CsvSpec(
    "olist_order_reviews_dataset.csv",
    (
        "review_id",
        "order_id",
        "review_score",
        "review_comment_title",
        "review_comment_message",
        "review_creation_date",
        "review_answer_timestamp",
    ),
    99_224,
    "review_id",
)

PRODUCTS = CsvSpec(
    "olist_products_dataset.csv",
    (
        "product_id",
        "product_category_name",
        # Misspelled in the source. Left alone deliberately - see the module docstring.
        "product_name_lenght",
        "product_description_lenght",
        "product_photos_qty",
        "product_weight_g",
        "product_length_cm",
        "product_height_cm",
        "product_width_cm",
    ),
    32_951,
    "product_id",
)

SELLERS = CsvSpec(
    "olist_sellers_dataset.csv",
    ("seller_id", "seller_zip_code_prefix", "seller_city", "seller_state"),
    3_095,
    "seller_id",
)

CATEGORY_TRANSLATION = CsvSpec(
    "product_category_name_translation.csv",
    ("product_category_name", "product_category_name_english"),
    71,
    "product_category_name",
)

ALL_SPECS: tuple[CsvSpec, ...] = (
    CUSTOMERS,
    GEOLOCATION,
    ORDERS,
    ORDER_ITEMS,
    ORDER_PAYMENTS,
    ORDER_REVIEWS,
    PRODUCTS,
    SELLERS,
    CATEGORY_TRANSLATION,
)

#: The subset the platform actually loads. Geolocation and reviews are downloaded but not ingested:
#: geolocation is a million rows of zip-code centroids that only dim_geography needs (Step 8 derives
#: it from the customer table's state instead), and reviews have no service that owns them yet.
#: Listed rather than silently ignored, because "we did not use it" and "we forgot it" look the same
#: in a loader that just does not mention a file.
LOADED_SPECS: tuple[CsvSpec, ...] = (
    CUSTOMERS,
    ORDERS,
    ORDER_ITEMS,
    ORDER_PAYMENTS,
    PRODUCTS,
    SELLERS,
    CATEGORY_TRANSLATION,
)

#: The 71 real Olist categories, Portuguese -> English, exactly as
#: product_category_name_translation.csv has them. Embedded so the synthetic generator produces a
#: genuine category vocabulary rather than invented labels: the categories are the one part of the
#: product data that is real text, and they are what Step 7's search is demonstrated against.
CATEGORY_TRANSLATIONS: dict[str, str] = {
    "beleza_saude": "health_beauty",
    "informatica_acessorios": "computers_accessories",
    "automotivo": "auto",
    "cama_mesa_banho": "bed_bath_table",
    "moveis_decoracao": "furniture_decor",
    "esporte_lazer": "sports_leisure",
    "perfumaria": "perfumery",
    "utilidades_domesticas": "housewares",
    "telefonia": "telephony",
    "relogios_presentes": "watches_gifts",
    "alimentos_bebidas": "food_drink",
    "bebes": "baby",
    "papelaria": "stationery",
    "tablets_impressao_imagem": "tablets_printing_image",
    "brinquedos": "toys",
    "telefonia_fixa": "fixed_telephony",
    "ferramentas_jardim": "garden_tools",
    "fashion_bolsas_e_acessorios": "fashion_bags_accessories",
    "eletroportateis": "small_appliances",
    "consoles_games": "consoles_games",
    "audio": "audio",
    "fashion_calcados": "fashion_shoes",
    "cool_stuff": "cool_stuff",
    "malas_acessorios": "luggage_accessories",
    "climatizacao": "air_conditioning",
    "construcao_ferramentas_construcao": "construction_tools_construction",
    "moveis_cozinha_area_de_servico_jantar_e_jardim": "kitchen_dining_laundry_garden_furniture",
    "construcao_ferramentas_jardim": "costruction_tools_garden",
    "fashion_roupa_masculina": "fashion_male_clothing",
    "pet_shop": "pet_shop",
    "moveis_escritorio": "office_furniture",
    "market_place": "market_place",
    "eletronicos": "electronics",
    "eletrodomesticos": "home_appliances",
    "artigos_de_festas": "party_supplies",
    "casa_conforto": "home_confort",
    "construcao_ferramentas_ferramentas": "costruction_tools_tools",
    "agro_industria_e_comercio": "agro_industry_and_commerce",
    "moveis_colchao_e_estofado": "furniture_mattress_and_upholstery",
    "livros_tecnicos": "books_technical",
    "casa_construcao": "home_construction",
    "instrumentos_musicais": "musical_instruments",
    "moveis_sala": "furniture_living_room",
    "construcao_ferramentas_iluminacao": "construction_tools_lights",
    "industria_comercio_e_negocios": "industry_commerce_and_business",
    "alimentos": "food",
    "artes": "art",
    "moveis_quarto": "furniture_bedroom",
    "livros_interesse_geral": "books_general_interest",
    "construcao_ferramentas_seguranca": "construction_tools_safety",
    "fashion_underwear_e_moda_praia": "fashion_underwear_beach",
    "fashion_esporte": "fashion_sport",
    "sinalizacao_e_seguranca": "signaling_and_security",
    "pcs": "computers",
    "artigos_de_natal": "christmas_supplies",
    "fashion_roupa_feminina": "fashio_female_clothing",
    "eletrodomesticos_2": "home_appliances_2",
    "livros_importados": "books_imported",
    "bebidas": "drinks",
    "cine_foto": "cine_photo",
    "la_cuisine": "la_cuisine",
    "musica": "music",
    "casa_conforto_2": "home_comfort_2",
    "portateis_casa_forno_e_cafe": "small_appliances_home_oven_and_coffee",
    "cds_dvds_musicais": "cds_dvds_musicals",
    "dvds_blu_ray": "dvds_blu_ray",
    "flores": "flowers",
    "artes_e_artesanato": "arts_and_craftmanship",
    "fraldas_higiene": "diapers_and_hygiene",
    "fashion_roupa_infanto_juvenil": "fashion_childrens_clothes",
    "seguros_e_servicos": "security_and_services",
}

#: The 27 Brazilian federative units, with the population weights the dataset roughly reflects.
#: Real geography, so "orders from Sao Paulo" is a question with a real answer.
BR_STATES: tuple[tuple[str, str, float], ...] = (
    ("SP", "sao paulo", 0.420),
    ("RJ", "rio de janeiro", 0.129),
    ("MG", "belo horizonte", 0.117),
    ("RS", "porto alegre", 0.055),
    ("PR", "curitiba", 0.050),
    ("SC", "florianopolis", 0.036),
    ("BA", "salvador", 0.034),
    ("DF", "brasilia", 0.021),
    ("ES", "vitoria", 0.020),
    ("GO", "goiania", 0.020),
    ("PE", "recife", 0.017),
    ("CE", "fortaleza", 0.013),
    ("PA", "belem", 0.010),
    ("MT", "cuiaba", 0.009),
    ("MA", "sao luis", 0.007),
    ("MS", "campo grande", 0.007),
    ("PB", "joao pessoa", 0.005),
    ("PI", "teresina", 0.005),
    ("RN", "natal", 0.005),
    ("AL", "maceio", 0.004),
    ("SE", "aracaju", 0.003),
    ("TO", "palmas", 0.003),
    ("RO", "porto velho", 0.003),
    ("AM", "manaus", 0.003),
    ("AC", "rio branco", 0.001),
    ("AP", "macapa", 0.001),
    ("RR", "boa vista", 0.001),
)

#: Order status vocabulary, with the proportions the real dataset has: the overwhelming majority of
#: orders are `delivered`, which is what makes "how many shipped late" a question with a small, real
#: answer rather than a coin flip.
ORDER_STATUSES: tuple[tuple[str, float], ...] = (
    ("delivered", 0.9700),
    ("shipped", 0.0110),
    ("canceled", 0.0063),
    ("unavailable", 0.0061),
    ("invoiced", 0.0031),
    ("processing", 0.0030),
    ("created", 0.00005),
    ("approved", 0.00002),
)

PAYMENT_TYPES: tuple[tuple[str, float], ...] = (
    ("credit_card", 0.7392),
    ("boleto", 0.1904),
    ("voucher", 0.0554),
    ("debit_card", 0.0150),
)
