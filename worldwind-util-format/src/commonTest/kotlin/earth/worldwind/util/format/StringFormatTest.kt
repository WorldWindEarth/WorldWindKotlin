package earth.worldwind.util.format

import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.offsetAt
import kotlinx.datetime.toInstant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

internal class StringFormatTest {
    @Test
    fun testSimpleFormat() {
        assertEquals("hello", "hello".format())
        assertEquals("% percent", "%% percent".format())
        assertEquals("10%", "10%%".format())
    }

    @Test
    fun testIntegers() {
        assertEquals("1 2 3", "%d %d %d".format(1,2,3))

        assertEquals("== 3 ==","== %d ==".format(3))
        assertEquals("==   3 ==","== %3d ==".format(3))
        assertEquals("== 003 ==","== %03d ==".format(3))
        assertEquals("== 3   ==","== %-3d ==".format(3))
        assertEquals("==  3  ==","== %^3d ==".format(3))
        assertEquals("== **3** ==","== %*^5d ==".format(3))
        assertEquals("== __3__ ==","== %_^5d ==".format(3))

        assertEquals("== +3 ==","== %+d ==".format(3))
        assertEquals("==    +3 ==","== %+5d ==".format(3))
        assertEquals("== +0003 ==","== %+05d ==".format(3))

        assertEquals("== 1e ==","== %x ==".format(0x1e))
        assertEquals("== 1E ==","== %X ==".format(0x1e))
        assertEquals("01 ff", "%02x %02x".format(1, 255))

        assertEquals("== ###1e ==","== %#5x ==".format(0x1e))
        assertEquals("== 1e### ==","== %#-5x ==".format(0x1e))
        assertEquals("== ##1E## ==","== %#^6X ==".format(0x1e))
    }

    @Test
    fun testFractionedDecimals() {
        assertEquals("0.124", fractionalFormat(0.1237, -1, 3))
        assertEquals("0.1237", fractionalFormat(0.1237, -1, 4))
        assertEquals("0.12370", fractionalFormat(0.1237, -1, 5))
        assertEquals("0.123700", fractionalFormat(0.1237, -1, 6))
        assertEquals("0.1", fractionalFormat(0.1237, -1, 1))
        assertEquals("0.12", fractionalFormat(0.1237, 4, -1))
        assertEquals("-0.1", fractionalFormat(-0.1237, 4, -1))

        assertEquals("-0.124", fractionalFormat(-0.1237, -1, 3))
        assertEquals("-0.1237", fractionalFormat(-0.1237, -1, 4))

        assertEquals("-221.12", fractionalFormat(-221.1217, -1, 2))
        assertEquals("-221.122", fractionalFormat(-221.1217, -1, 3))

        assertEquals("221.122", fractionalFormat(221.1217, -1, 3))
        assertEquals("221.1217", fractionalFormat(221.1217, -1, 4))

        assertEquals("221.122", "%.3f".format(221.1217))
        assertEquals("__221.1", "%_7.1f".format(221.1217))
        assertEquals("_+221.1", "%+_7.1f".format(221.1217))
        assertEquals("+0221.1", "%+07.1f".format(221.1217))
        assertEquals("00221.1", "%07.1f".format(221.1217))

        assertEquals("1.000", "%.3f".format(1))
    }

    @Test
    fun testScientificDecimals() {
        val x = ExponentFormatter(-162.345678)
        fun test(n: Int,expected: String) {
            assertEquals(expected, x.scientific(n))
        }
        test(3, "-2e2")
        test(4, "-2e2")
        test(5, "-2.e2")
        test(6, "-1.6e2")
        test(7, "-1.62e2")
        test(8, "-1.623e2")
        test(9, "-1.6235e2")
        test(10, "-1.62346e2")
        test(11, "-1.623457e2")
        test(12, "-1.6234568e2")
        test(13, "-1.62345678e2")
        test(14, "-1.62345678e2")
        test(15, "-1.62345678e2")

        assertEquals("2.4e0", scientificFormat(2.39, 5) )
        assertEquals("-2.4e-3", scientificFormat(-2.39e-3, 7) )
        assertEquals("2.4e-3", scientificFormat(2.39e-3, 6) )

        assertEquals("-2.4e-3", scientificFormat(-2.39e-3, -1, 1) )
        assertEquals("-2.39e-3", scientificFormat(-2.39e-3, -1, 2) )

        assertEquals("-2.39e-3", "%.2e".format(-2.39e-3, -1, 2) )
        assertEquals("2.4e-3", "%6e".format(2.39e-3))

        assertEquals("-2.39E-3", "%.2E".format(-2.39e-3) )
        assertEquals("2.39E-3", "%.2E".format(2.39e-3) )
        assertEquals("+2.39E-3", "%+.2E".format(2.39e-3) )

        assertEquals("2.4E-3", "%6E".format(2.39e-3))
        assertEquals("0002.4E-3", "%09.1E".format(2.39e-3))
        assertEquals("+002.4E-3", "%+09.1E".format(2.39e-3))
    }

    @Test
    fun testSientificFormat() {
        val x = ExponentFormatter(3.349832285740512)
        assertEquals("3.350e0", x.scientific(7))

        assertEquals("3.350e0", x.scientific(7))

        assertEquals("9.998e0", ExponentFormatter(9.9980).scientific(7))
        assertEquals("9.998e0", ExponentFormatter(9.9981).scientific(7))
        assertEquals("9.998e0", ExponentFormatter(9.9982).scientific(7))
        assertEquals("9.998e0", ExponentFormatter(9.9983).scientific(7))
        assertEquals("9.998e0", ExponentFormatter(9.9984).scientific(7))
        assertEquals("9.999e0", ExponentFormatter(9.9985).scientific(7))
        assertEquals("9.999e0", ExponentFormatter(9.9986).scientific(7))
        assertEquals("9.999e0", ExponentFormatter(9.9987).scientific(7))
        assertEquals("9.999e0", ExponentFormatter(9.9988).scientific(7))
        assertEquals("9.999e0", ExponentFormatter(9.9989).scientific(7))

        assertEquals("9.939e0", ExponentFormatter(9.9390).scientific(7))
        assertEquals("9.939e0", ExponentFormatter(9.9391).scientific(7))
        assertEquals("9.939e0", ExponentFormatter(9.9392).scientific(7))
        assertEquals("9.939e0", ExponentFormatter(9.9393).scientific(7))
        assertEquals("9.939e0", ExponentFormatter(9.9394).scientific(7))
        assertEquals("9.940e0", ExponentFormatter(9.9395).scientific(7))
        assertEquals("9.940e0", ExponentFormatter(9.9396).scientific(7))
        assertEquals("9.940e0", ExponentFormatter(9.9397).scientific(7))
        assertEquals("9.940e0", ExponentFormatter(9.9398).scientific(7))
        assertEquals("9.940e0", ExponentFormatter(9.9399).scientific(7))

        assertEquals("9.399e0", ExponentFormatter(9.3990).scientific(7))
        assertEquals("9.399e0", ExponentFormatter(9.3991).scientific(7))
        assertEquals("9.399e0", ExponentFormatter(9.3992).scientific(7))
        assertEquals("9.399e0", ExponentFormatter(9.3993).scientific(7))
        assertEquals("9.399e0", ExponentFormatter(9.3994).scientific(7))
        assertEquals("9.400e0", ExponentFormatter(9.3995).scientific(7))
        assertEquals("9.400e0", ExponentFormatter(9.3995).scientific(7))
        assertEquals("9.400e0", ExponentFormatter(9.3996).scientific(7))
        assertEquals("9.400e0", ExponentFormatter(9.3997).scientific(7))
        assertEquals("9.400e0", ExponentFormatter(9.3998).scientific(7))
        assertEquals("9.400e0", ExponentFormatter(9.3999).scientific(7))

        assertEquals("9.999e0", ExponentFormatter(9.9990).scientific(7))
        assertEquals("9.999e0", ExponentFormatter(9.9991).scientific(7))
        assertEquals("9.999e0", ExponentFormatter(9.9992).scientific(7))
        assertEquals("9.999e0", ExponentFormatter(9.9993).scientific(7))
        assertEquals("9.999e0", ExponentFormatter(9.9994).scientific(7))
        assertEquals("1.000e1", ExponentFormatter(9.9995).scientific(7))
        assertEquals("1.000e1", ExponentFormatter(9.9996).scientific(7))
        assertEquals("1.000e1", ExponentFormatter(9.9995).scientific(7))
        assertEquals("1.000e1", ExponentFormatter(9.9998).scientific(7))
        assertEquals("1.000e1", ExponentFormatter(9.9999).scientific(7))

        assertEquals("3.350e0", x.scientific(7))

        assertEquals("3.350e0", "%.3e".format(3.349832285740512 ))
        assertEquals("1.000e1", "%.3e".format(9.9999 ))
    }

    @Test
    fun testFractionalFormat() {
        assertEquals("3.340", "%.3f".format(3.34011 ))
        assertEquals("3.340", "%.3f".format(3.34022 ))
        assertEquals("3.340", "%.3f".format(3.34033 ))
        assertEquals("3.340", "%.3f".format(3.34044 ))
        assertEquals("3.341", "%.3f".format(3.34054 ))
        assertEquals("3.341", "%.3f".format(3.34065 ))
        assertEquals("3.341", "%.3f".format(3.34076 ))
        assertEquals("3.341", "%.3f".format(3.34087 ))
        assertEquals("3.341", "%.3f".format(3.34098 ))

        assertEquals("3.399", "%.3f".format(3.39911 ))
        assertEquals("3.399", "%.3f".format(3.39921 ))
        assertEquals("3.399", "%.3f".format(3.39931 ))
        assertEquals("3.399", "%.3f".format(3.39942 ))

        assertEquals("3.400", "%.3f".format(3.39951 ))
        assertEquals("3.400", "%.3f".format(3.39961 ))
        assertEquals("3.400", "%.3f".format(3.39971 ))
        assertEquals("3.400", "%.3f".format(3.39981 ))
        assertEquals("3.400", "%.3f".format(3.39991 ))

        assertEquals("10.0", "%.1f".format(9.99991 ))
        assertEquals("30.0", "%.1f".format(533.0 / 6400 * 360))
    }

    @Test
    fun testAutoFloats() {
        assertEquals("17.234", "%g".format(17.234))
        assertEquals("**17.234", "%*8g".format(17.234))
        assertEquals("+017.234", "%+08g".format(17.234))
    }

    @Test
    fun testStrings() {
        assertEquals("== 3 ==","== %s ==".format(3))
        assertEquals("==   3 ==","== %3s ==".format(3))
        assertEquals("== 3   ==","== %-3s ==".format(3))
        assertEquals("==  3  ==","== %^3s ==".format(3))
        assertEquals("== **3** ==","== %*^5s ==".format(3))
        assertEquals("== __3__ ==","== %_^5s ==".format(3))
        assertEquals("*****hello!","%*10s!".format("hello"))
        assertEquals("Hello, world!","%s, %s!".format("Hello", "world"))
        assertEquals("___centered___","%^_14s".format("centered"))
    }

    @Test
    fun testCharacters() {
        assertEquals("Cat!", "Ca%c!".format('t'))
        assertEquals("Cat!", "Ca%C!".format('t'))
    }

    @Test
    fun testOctals() {
        assertEquals("7 10", "%o %o".format(7,8))
        assertEquals("007 010", "%03o %03o".format(7,8))
    }

    @Test
    fun testTime() {
        val t = LocalDateTime(1970, 5, 6, 5, 45, 11, 123456789 )
        assertEquals("05:45:11.123 (123456789)","%1\$tH:%1\$tM:%1\$tS.%1\$tL (%1\$tN)".format(t))
        assertEquals("05:45:11.123 (123456789)","%1!tH:%1!tM:%1!tS.%1!tL (%1!tN)".format(t))

        assertEquals("May 6, 1970","%1!tB %1!te, %1!tY".format(t))
        assertEquals("06.05.1970","%1!td.%1!tm.%1!tY".format(t))
        assertEquals("06.05.70","%1!td.%1!tm.%1!ty".format(t))
        assertEquals("06.May.70","%1!td.%1!th.%1!ty".format(t))
        assertEquals("06.May.70","%1!td.%1!tB.%1!ty".format(t))
        assertEquals("Day 126, it was Wednesday.","Day %tj, it was %1!tA.".format(t))
        assertEquals("Day 126, it was Wed.","Day %tj, it was %1!ta.".format(t))

        assertEquals("05:45","%tR".format(t))
        assertEquals("05:45:11","%tT".format(t))

        val t1 = LocalDateTime(1970, 5, 6, 15, 45, 11, 123456789)
        assertEquals("05:45:11 AM","%Tr".format(t))
        assertEquals("03:45:11 pm","%tr".format(t1))

        assertEquals("05/06/70","%tD".format(t))
        assertEquals("1970-05-06","%tF".format(t))
        val tz = TimeZone.currentSystemDefault()
        val offset = tz.offsetAt(t.toInstant(tz)).toString()
        assertEquals("Wed May 06 05:45:11 $offset 1970","%tc".format(t))

        assertTrue { "%tO".format(t).startsWith("1970-05-06T05:45:11") }
    }
}