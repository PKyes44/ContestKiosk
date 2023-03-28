package com.yes.visionvoicedemo


import org.openkoreantext.processor.KoreanTokenJava
import org.openkoreantext.processor.OpenKoreanTextProcessorJava
import org.openkoreantext.processor.tokenizer.KoreanTokenizer.KoreanToken
import scala.collection.immutable.Seq

class STTActivity {
    val NUMBERS = mapOf (
        "한" to 1, "하나" to 1,
        "두" to 2, "둘" to 2,
        "세" to 3, "셋" to 3,
        "네" to 4, "넷" to 4,
        "다섯" to 5,
        "여섯" to 6,
        "일곱" to 7,
        "여덟" to 8,
        "아홉" to 9
    )
    val NUMBERS_STR = mapOf (
        "열" to 10, "십" to 10,
        "스무" to 20, "스물" to 20, "이십" to 20,
        "서른" to 30, "삼십" to 30,
        "마흔" to 40, "사십" to 40,
        "쉰" to 50, "오십" to 50,
        "백" to 100, "천" to 1000, "만" to 10000)

    val ADD_CART = listOf("추가", "주세요", "주문", "넣다", "담다", "줄다", "주다", "내놓다", "놓다")
    val REDUCE_CART = listOf("제거하다", "없애다", "빼다", "삭제하다", "제외하다", "빼주세요")
    val STOP_WORD = listOf("그리고", "장바구니", "메뉴", "상품", "메뉴판", "있다","다음","에","개","잔","와","랑","과","목록","보이다","알다","목록")

    val VIEW_CART_PATTERN = Regex("(장바구니)(.*)")
    val VIEW_MENU_PATTERN = Regex("(메뉴|메뉴판|상품)(.*)")

    fun analyzeSentence(sentence: String): MutableList<KoreanTokenJava>? {

        // 정규화
        val normalized = OpenKoreanTextProcessorJava.normalize(sentence)
        // 토큰화
        val tokenize: Seq<KoreanToken> =
            OpenKoreanTextProcessorJava.tokenize(normalized) as Seq<KoreanToken>
        // 어간 추출
        val tokens = OpenKoreanTextProcessorJava.tokensToJavaKoreanTokenList(tokenize)
        return tokens
    }

    //  정보 추출
    fun extractProductInfo(morphemes: MutableList<KoreanTokenJava>?): MutableList<Pair<String, String>> {
        var productName: String? = null
        var productCount: Int? = null

        val correctedTokens = mutableListOf<Pair<String, String>>()

        // 상품 수량 추출
        if (morphemes != null) {
            for (morpheme in morphemes) {
                var sum = 0
                for (number in NUMBERS) {
                    if (number.key == morpheme.text) {
                        sum += NUMBERS[number.key]!!
                    }
                }
                for (number in NUMBERS_STR) {
                    if (number.equals(morpheme.text)) {
                        sum += NUMBERS[number.key]!!
                    }
                }
                if (sum != 0) {
                    correctedTokens.add(Pair(sum.toString(), "Number"))
                } else {
                    if (morpheme.stem.isNotEmpty()) {
                        correctedTokens.add(Pair(morpheme.stem, "Noun"))
                    } else {
                        correctedTokens.add(Pair(morpheme.text, "Noun"))
                    }
                }
            }
        }

        return correctedTokens
    }

    fun processOrder(sentence: String): androidx.core.util.Pair<MutableList<androidx.core.util.Pair<String, Int>>, MutableList<String>> {
        var orderInfo = mutableListOf<androidx.core.util.Pair<String, Int>>()
        var productNameSave = mutableListOf<String>()
        var products = mutableListOf<androidx.core.util.Pair<String, Int>>()
        var viewCart = false
        var viewMenu = false

        val morphemes = analyzeSentence(sentence)
        val tokens = extractProductInfo(morphemes)
        for (token in tokens) {
            // 메뉴 확인 의도
            if (VIEW_MENU_PATTERN.matches(token.first)) {
                viewMenu = true
            }
            // 장바구니 확인 의도
            if (VIEW_CART_PATTERN.matches(token.first)) {
                viewCart = true
            }
            // 불용어 제거
            if (token.first in STOP_WORD) {
                continue
            }
            // 추가 / 제거 의도 단어 판별
            if ((token.first in ADD_CART || token.first in REDUCE_CART) && (products.isNotEmpty())) {
                if (token.first in ADD_CART) {
                    for (product in products) {
                        print(product)
                        orderInfo.add(product)
                    }
                } else if (token.first in REDUCE_CART) {
                    for (product in products) {
                        var pd = androidx.core.util.Pair(product.first, product.second?.times(-1))
                        orderInfo.add(pd)
                    }
                }
                products.clear()
                productNameSave.clear()
            }
            // 추가 / 제거 의도 판별되었으나 수량이 제시되지 않은 경우
            else if ((token.first in ADD_CART || token.first in REDUCE_CART) && (productNameSave.isNotEmpty())) {
                for (product in productNameSave) {
                    if (token.first in ADD_CART) {
                        orderInfo.add(androidx.core.util.Pair(product, 1))
                    } else {
                        orderInfo.add(androidx.core.util.Pair(product, -1))
                    }
                }
                productNameSave.clear()
            }
            // 추가 / 제거 의도 단어 아닐 경우
            else {
                if (token.second == "Number") {
                    for (product in productNameSave) {
                        var alreadyProduct = false
                        for (pd in products) {
                            if (product == pd.first) {
                                alreadyProduct = true
                            }
                        }
                        if (!alreadyProduct) {
                            print(product)
                            products.add(androidx.core.util.Pair(product, token.first.toInt()))
                        }
                    }
                }
                else {
                    productNameSave.add(token.first)
                    continue
                }
            }
        }

        var addMentation = ""
        var reduceMentaton = ""
        var ments = mutableListOf<String>()

        for (order in orderInfo) {
            if (order.second!! >= 1) {
                addMentation += order.first + " " + order.second.toString() + "개 "
            } else {
                reduceMentaton += order.first + " " + (order.second!! *-1).toString() + "개 "
            }
        }
        if (addMentation.isNotEmpty() || reduceMentaton.isNotEmpty()) {
            if (addMentation.isNotEmpty()) {
                addMentation += "를 추가하셨습니다"
                ments.add(addMentation)
            }
            if (reduceMentaton.isNotEmpty()) {
                reduceMentaton += "를 제거하셨습니다"
                ments.add(reduceMentaton)
            }
        } else {
            if (viewMenu) {
                ments.add("잠시 후 메뉴 목록을 알려드리겠습니다")
            } else if (viewCart) {
                ments.add("잠시 후 장바구니 목록을 알려드리겠습니다.")
            }
        }
        if (ments.isEmpty()) {
            ments.add("죄송합니다. 다시 한번 말씀해주십시오")
        }
        return androidx.core.util.Pair(orderInfo, ments)
    }


}