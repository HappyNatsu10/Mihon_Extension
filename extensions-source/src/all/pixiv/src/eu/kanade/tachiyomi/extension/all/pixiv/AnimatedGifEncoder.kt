package eu.kanade.tachiyomi.extension.all.pixiv

import android.graphics.Bitmap
import java.io.OutputStream
import kotlin.math.abs

/**
 * Pure Kotlin Animated GIF Encoder (GIF89a).
 * Streams frames one by one without holding multiple Bitmaps in memory.
 */
internal class AnimatedGifEncoder {

    private var width = 0
    private var height = 0
    private var transparent: Int? = null
    private var transIndex = 0
    private var repeat = 0 // 0 = repeat indefinitely
    private var delay = 0 // frame delay in 1/100 sec
    private var started = false
    private var out: OutputStream? = null
    private var pixels: ByteArray? = null
    private var indexedPixels: ByteArray? = null
    private var colorDepth = 8
    private var colorTab: ByteArray? = null
    private var usedEntry = BooleanArray(256)
    private var palSize = 7
    private var dispose = -1 // disposal code (-1 = default, 1 = do not dispose, 2 = restore to background)
    private var firstFrame = true
    private var sample = 10 // quality sample (1 = best, 30 = fastest)

    fun setDelay(ms: Int) {
        delay = (ms + 5) / 10
        if (delay < 1) delay = 1
    }

    fun setDispose(code: Int) {
        if (code >= 0) dispose = code
    }

    fun setRepeat(iter: Int) {
        if (iter >= 0) repeat = iter
    }

    fun setQuality(quality: Int) {
        sample = if (quality < 1) 1 else quality
    }

    fun start(os: OutputStream): Boolean {
        out = os
        firstFrame = true
        started = true
        return writeString("GIF89a")
    }

    fun addFrame(im: Bitmap): Boolean {
        if (!started || out == null) return false
        val w = im.width
        val h = im.height
        val rgb = IntArray(w * h)
        im.getPixels(rgb, 0, w, 0, 0, w, h)
        return addFrame(rgb, w, h)
    }

    fun addFrame(rgb: IntArray, frameWidth: Int, frameHeight: Int): Boolean {
        if (!started || out == null) return false
        try {
            if (firstFrame) {
                width = frameWidth
                height = frameHeight
            }
            getImagePixels(rgb)
            analyzePixels()
            if (firstFrame) {
                writeLSD()
                writePalette()
                if (repeat >= 0) {
                    writeNetscapeExt()
                }
            }
            writeGraphicCtrlExt()
            writeImageDesc()
            if (!firstFrame) {
                writePalette()
            }
            writePixels()
            firstFrame = false
            return true
        } catch (e: Exception) {
            e.printStackTrace()
            return false
        }
    }

    fun finish(): Boolean {
        if (!started) return false
        started = false
        var ok = true
        try {
            out?.write(0x3B) // gif trailer
            out?.flush()
        } catch (_: Exception) {
            ok = false
        }
        transIndex = 0
        out = null
        pixels = null
        indexedPixels = null
        colorTab = null
        firstFrame = true
        return ok
    }

    private fun analyzePixels() {
        val len = pixels!!.size
        val nPix = len / 3
        indexedPixels = ByteArray(nPix)
        val nq = NeuQuant(pixels!!, len, sample)
        colorTab = nq.process()

        for (i in 0 until colorTab!!.size step 3) {
            val temp = colorTab!![i]
            colorTab!![i] = colorTab!![i + 2]
            colorTab!![i + 2] = temp
            usedEntry[i / 3] = false
        }

        var k = 0
        for (i in 0 until nPix) {
            val index = nq.map(
                pixels!![k++].toInt() and 0xff,
                pixels!![k++].toInt() and 0xff,
                pixels!![k++].toInt() and 0xff,
            )
            usedEntry[index] = true
            indexedPixels!![i] = index.toByte()
        }
        pixels = null
        colorDepth = 8
        palSize = 7

        transparent?.let {
            transIndex = findClosest(it)
        }
    }

    private fun findClosest(color: Int): Int {
        if (colorTab == null) return -1
        val r = (color shr 16) and 0xff
        val g = (color shr 8) and 0xff
        val b = color and 0xff
        var minpos = 0
        var dmin = 256 * 256 * 256
        val len = colorTab!!.size
        for (i in 0 until len step 3) {
            val dr = r - (colorTab!![i].toInt() and 0xff)
            val dg = g - (colorTab!![i + 1].toInt() and 0xff)
            val db = b - (colorTab!![i + 2].toInt() and 0xff)
            val d = dr * dr + dg * dg + db * db
            val index = i / 3
            if (usedEntry[index] && (d < dmin)) {
                dmin = d
                minpos = index
            }
        }
        return minpos
    }

    private fun getImagePixels(rgb: IntArray) {
        pixels = ByteArray(rgb.size * 3)
        var count = 0
        for (i in rgb.indices) {
            val color = rgb[i]
            pixels!![count++] = (color and 0xFF).toByte() // B
            pixels!![count++] = ((color shr 8) and 0xFF).toByte() // G
            pixels!![count++] = ((color shr 16) and 0xFF).toByte() // R
        }
    }

    private fun writeGraphicCtrlExt() {
        out!!.write(0x21) // extension introducer
        out!!.write(0xf9) // GCE label
        out!!.write(4) // data block size
        val transp: Int
        var disp: Int
        if (transparent == null) {
            transp = 0
            disp = 0 // dispose = no action
        } else {
            transp = 1
            disp = 2 // force clear if using transparent color
        }
        if (dispose >= 0) {
            disp = dispose and 7
        }
        disp = disp shl 2

        out!!.write(0 or disp or 0 or transp)
        writeShort(delay)
        out!!.write(transIndex)
        out!!.write(0) // block terminator
    }

    private fun writeImageDesc() {
        out!!.write(0x2c) // image separator
        writeShort(0) // image left
        writeShort(0) // image top
        writeShort(width) // image width
        writeShort(height) // image height
        if (firstFrame) {
            out!!.write(0) // no local color table
        } else {
            out!!.write(0x80 or 0 or 0 or 0 or palSize) // local color table
        }
    }

    private fun writeLSD() {
        writeShort(width)
        writeShort(height)
        out!!.write(0x80 or 0x70 or 0x00 or palSize) // GCT flag + color resolution + size
        out!!.write(0) // background color index
        out!!.write(0) // pixel aspect ratio
    }

    private fun writeNetscapeExt() {
        out!!.write(0x21) // extension introducer
        out!!.write(0xff) // app extension label
        out!!.write(11) // block size
        writeString("NETSCAPE2.0")
        out!!.write(3) // sub-block size
        out!!.write(1) // loop sub-block id
        writeShort(repeat) // loop count
        out!!.write(0) // block terminator
    }

    private fun writePalette() {
        out!!.write(colorTab!!, 0, colorTab!!.size)
        val n = (3 * 256) - colorTab!!.size
        for (i in 0 until n) {
            out!!.write(0)
        }
    }

    private fun writePixels() {
        val encoder = LzwEncoder(width, height, indexedPixels!!, colorDepth)
        encoder.encode(out!!)
    }

    private fun writeShort(value: Int) {
        out!!.write(value and 0xff)
        out!!.write((value shr 8) and 0xff)
    }

    private fun writeString(s: String): Boolean = try {
        for (i in s.indices) {
            out!!.write(s[i].code)
        }
        true
    } catch (_: Exception) {
        false
    }
}

/**
 * LZW encoder for GIF table-based image data.
 */
private class LzwEncoder(
    private val imgW: Int,
    private val imgH: Int,
    private val pixAry: ByteArray,
    private val initCodeSize: Int,
) {
    private var curPixel = 0
    private var remaining = imgW * imgH
    private val initCodeSizeParam = initCodeSize.coerceAtLeast(2)

    private val bits = 12
    private val hsize = 5003

    private var nBits = 0
    private var maxbits = bits
    private var maxcode = 0
    private var maxmaxcode = 1 shl bits

    private val htab = IntArray(hsize)
    private val codetab = IntArray(hsize)

    private var freeEnt = 0
    private var clearFlg = false

    private var gInitBits = 0
    private var clearCode = 0
    private var eofCode = 0

    private var curAccum = 0
    private var curBits = 0

    private val masks = intArrayOf(
        0x0000, 0x0001, 0x0003, 0x0007, 0x000F,
        0x001F, 0x003F, 0x007F, 0x00FF, 0x01FF,
        0x03FF, 0x07FF, 0x0FFF, 0x1FFF, 0x3FFF,
        0x7FFF, 0xFFFF,
    )

    private var aCount = 0
    private val accum = ByteArray(256)

    fun encode(os: OutputStream) {
        os.write(initCodeSizeParam)
        remaining = imgW * imgH
        curPixel = 0
        compress(initCodeSizeParam + 1, os)
        os.write(0) // write block terminator
    }

    private fun charOut(c: Byte, outs: OutputStream) {
        accum[aCount++] = c
        if (aCount >= 254) flushChar(outs)
    }

    private fun clHash(hsizeParam: Int) {
        for (i in 0 until hsizeParam) htab[i] = -1
    }

    private fun compress(initBits: Int, outs: OutputStream) {
        gInitBits = initBits
        clearFlg = false
        nBits = gInitBits
        maxcode = (1 shl nBits) - 1

        clearCode = 1 shl (initBits - 1)
        eofCode = clearCode + 1
        freeEnt = clearCode + 2

        aCount = 0

        var ent = nextPixel()
        var hshift = 0
        var fcode = hsize
        while (fcode < 65536) {
            hshift++
            fcode *= 2
        }
        hshift = 8 - hshift

        val hsizeReg = hsize
        clHash(hsizeReg)

        output(clearCode, outs)

        outer@ while (true) {
            val c = nextPixel()
            if (c == -1) break
            val fcodeVal = (c shl maxbits) + ent
            var i = (c shl hshift) xor ent

            if (htab[i] == fcodeVal) {
                ent = codetab[i]
                continue
            } else if (htab[i] >= 0) {
                var disp = hsizeReg - i
                if (i == 0) disp = 1
                do {
                    i -= disp
                    if (i < 0) i += hsizeReg
                    if (htab[i] == fcodeVal) {
                        ent = codetab[i]
                        continue@outer
                    }
                } while (htab[i] >= 0)
            }
            output(ent, outs)
            ent = c
            if (freeEnt < maxmaxcode) {
                codetab[i] = freeEnt++
                htab[i] = fcodeVal
            } else {
                clBlock(outs)
            }
        }
        output(ent, outs)
        output(eofCode, outs)
    }

    private fun clBlock(outs: OutputStream) {
        clHash(hsize)
        freeEnt = clearCode + 2
        clearFlg = true
        output(clearCode, outs)
    }

    private fun flushChar(outs: OutputStream) {
        if (aCount > 0) {
            outs.write(aCount)
            outs.write(accum, 0, aCount)
            aCount = 0
        }
    }

    private fun nextPixel(): Int {
        if (remaining == 0) return -1
        --remaining
        val pix = pixAry[curPixel++].toInt() and 0xff
        return pix
    }

    private fun output(code: Int, outs: OutputStream) {
        curAccum = curAccum and masks[curBits]
        if (curBits > 0) curAccum = curAccum or (code shl curBits) else curAccum = code
        curBits += nBits

        while (curBits >= 8) {
            charOut((curAccum and 0xff).toByte(), outs)
            curAccum = curAccum shr 8
            curBits -= 8
        }

        if (freeEnt > maxcode || clearFlg) {
            if (clearFlg) {
                nBits = gInitBits
                maxcode = (1 shl nBits) - 1
                clearFlg = false
            } else {
                ++nBits
                maxcode = if (nBits == maxbits) maxmaxcode else (1 shl nBits) - 1
            }
        }

        if (code == eofCode) {
            while (curBits > 0) {
                charOut((curAccum and 0xff).toByte(), outs)
                curAccum = curAccum shr 8
                curBits -= 8
            }
            flushChar(outs)
        }
    }
}

/**
 * NeuQuant Neural-Net Color Quantization algorithm by Anthony Dekker (1994).
 */
private class NeuQuant(
    private val thepicture: ByteArray,
    private val lengthcount: Int,
    private val samplefac: Int,
) {
    private val netsize = 256
    private val prime1 = 499
    private val prime2 = 491
    private val prime3 = 487
    private val prime4 = 503
    private val minpicturebytes = 3 * prime4

    private val maxnetpos = netsize - 1
    private val netbiasshift = 4
    private val ncycles = 100

    private val intbiasshift = 16
    private val intbias = 1 shl intbiasshift
    private val gammashift = 10
    private val betashift = 10
    private val beta = intbias shr betashift
    private val betagamma = intbias shl (gammashift - betashift)

    private val initrad = netsize shr 3
    private val radiusbiasshift = 6
    private val radiusbias = 1 shl radiusbiasshift
    private val initradius = initrad * radiusbias
    private val radiusdec = 30

    private val alphabiasshift = 10
    private val initalpha = 1 shl alphabiasshift
    private var alphadec = 0

    private val radbiasshift = 8
    private val radbias = 1 shl radbiasshift
    private val alpharadbshift = alphabiasshift + radbiasshift
    private val alpharadbias = 1 shl alpharadbshift

    private val network = Array(netsize) { IntArray(4) }
    private val netindex = IntArray(256)
    private val bias = IntArray(netsize)
    private val freq = IntArray(netsize)
    private val radpower = IntArray(initrad)

    init {
        for (i in 0 until netsize) {
            val v = (i shl (netbiasshift + 8)) / netsize
            network[i][0] = v
            network[i][1] = v
            network[i][2] = v
            freq[i] = intbias / netsize
            bias[i] = 0
        }
    }

    private fun unbiasnet() {
        for (i in 0 until netsize) {
            network[i][0] = network[i][0] shr netbiasshift
            network[i][1] = network[i][1] shr netbiasshift
            network[i][2] = network[i][2] shr netbiasshift
            network[i][3] = i
        }
    }

    private fun colorMap(): ByteArray {
        val map = ByteArray(3 * netsize)
        val index = IntArray(netsize)
        for (i in 0 until netsize) index[network[i][3]] = i
        var k = 0
        for (i in 0 until netsize) {
            val j = index[i]
            map[k++] = network[j][0].toByte()
            map[k++] = network[j][1].toByte()
            map[k++] = network[j][2].toByte()
        }
        return map
    }

    private fun inxbuild() {
        var previouscol = 0
        var startpos = 0
        for (i in 0 until netsize) {
            val p = network[i]
            var smallpos = i
            var smallval = p[1]
            for (j in (i + 1) until netsize) {
                val q = network[j]
                if (q[1] < smallval) {
                    smallpos = j
                    smallval = q[1]
                }
            }
            val q = network[smallpos]
            if (i != smallpos) {
                for (c in 0..3) {
                    val t = p[c]
                    p[c] = q[c]
                    q[c] = t
                }
            }
            if (smallval != previouscol) {
                netindex[previouscol] = (startpos + i) shr 1
                for (j in (previouscol + 1) until smallval) netindex[j] = i
                previouscol = smallval
                startpos = i
            }
        }
        netindex[previouscol] = (startpos + maxnetpos) shr 1
        for (j in (previouscol + 1)..255) netindex[j] = maxnetpos
    }

    private fun learn() {
        var sample = samplefac
        if (lengthcount < minpicturebytes) sample = 1
        alphadec = 30 + ((sample - 1) / 3)
        val lengthcountLocal = lengthcount
        val samplepixels = lengthcountLocal / (3 * sample)
        var delta = samplepixels / ncycles
        var alpha = initalpha
        var radius = initradius

        var rad = radius shr radiusbiasshift
        if (rad <= 1) rad = 0
        for (i in 0 until rad) {
            radpower[i] = alpha * (((rad * rad - i * i) * radbias) / (rad * rad))
        }

        val step: Int = if (lengthcountLocal % prime1 != 0) {
            3 * prime1
        } else if (lengthcountLocal % prime2 != 0) {
            3 * prime2
        } else if (lengthcountLocal % prime3 != 0) {
            3 * prime3
        } else {
            3 * prime4
        }

        var pix = 0
        var i = 0
        while (i < samplepixels) {
            val b = (thepicture[pix].toInt() and 0xff) shl netbiasshift
            val g = (thepicture[pix + 1].toInt() and 0xff) shl netbiasshift
            val r = (thepicture[pix + 2].toInt() and 0xff) shl netbiasshift
            val j = contest(b, g, r)

            altersingle(alpha, j, b, g, r)
            if (rad != 0) alterneigh(rad, j, b, g, r)

            pix += step
            if (pix >= lengthcountLocal) pix -= lengthcountLocal

            i++
            if (delta == 0) delta = 1
            if (i % delta == 0) {
                alpha -= alpha / alphadec
                radius -= radius / radiusdec
                rad = radius shr radiusbiasshift
                if (rad <= 1) rad = 0
                for (k in 0 until rad) {
                    radpower[k] = alpha * (((rad * rad - k * k) * radbias) / (rad * rad))
                }
            }
        }
    }

    fun map(b: Int, g: Int, r: Int): Int {
        var bestd = 1000
        var best = -1
        var i = netindex[g]
        var j = i - 1

        while (i < netsize || j >= 0) {
            if (i < netsize) {
                val p = network[i]
                var dist = p[1] - g
                if (dist >= bestd) {
                    i = netsize
                } else {
                    i++
                    if (dist < 0) dist = -dist
                    var a = p[0] - b
                    if (a < 0) a = -a
                    dist += a
                    if (dist < bestd) {
                        a = p[2] - r
                        if (a < 0) a = -a
                        dist += a
                        if (dist < bestd) {
                            bestd = dist
                            best = p[3]
                        }
                    }
                }
            }
            if (j >= 0) {
                val p = network[j]
                var dist = g - p[1]
                if (dist >= bestd) {
                    j = -1
                } else {
                    j--
                    if (dist < 0) dist = -dist
                    var a = p[0] - b
                    if (a < 0) a = -a
                    dist += a
                    if (dist < bestd) {
                        a = p[2] - r
                        if (a < 0) a = -a
                        dist += a
                        if (dist < bestd) {
                            bestd = dist
                            best = p[3]
                        }
                    }
                }
            }
        }
        return best
    }

    fun process(): ByteArray {
        learn()
        unbiasnet()
        inxbuild()
        return colorMap()
    }

    private fun altersingle(alpha: Int, i: Int, b: Int, g: Int, r: Int) {
        val n = network[i]
        n[0] -= (alpha * (n[0] - b)) / initalpha
        n[1] -= (alpha * (n[1] - g)) / initalpha
        n[2] -= (alpha * (n[2] - r)) / initalpha
    }

    private fun alterneigh(rad: Int, i: Int, b: Int, g: Int, r: Int) {
        var lo = i - rad
        if (lo < -1) lo = -1
        var hi = i + rad
        if (hi > netsize) hi = netsize

        var j = i + 1
        var k = i - 1
        var m = 1
        while (j < hi || k > lo) {
            val a = radpower[m++]
            if (j < hi) {
                val p = network[j++]
                try {
                    p[0] -= (a * (p[0] - b)) / alpharadbias
                    p[1] -= (a * (p[1] - g)) / alpharadbias
                    p[2] -= (a * (p[2] - r)) / alpharadbias
                } catch (_: Exception) {}
            }
            if (k > lo) {
                val p = network[k--]
                try {
                    p[0] -= (a * (p[0] - b)) / alpharadbias
                    p[1] -= (a * (p[1] - g)) / alpharadbias
                    p[2] -= (a * (p[2] - r)) / alpharadbias
                } catch (_: Exception) {}
            }
        }
    }

    private fun contest(b: Int, g: Int, r: Int): Int {
        var bestd = Int.MAX_VALUE
        var bestbiasd = bestd
        var bestpos = -1
        var bestbiaspos = bestpos

        for (i in 0 until netsize) {
            val n = network[i]
            var dist = abs(n[0] - b) + abs(n[1] - g) + abs(n[2] - r)
            if (dist < bestd) {
                bestd = dist
                bestpos = i
            }
            val biasdist = dist - (bias[i] shr (intbiasshift - netbiasshift))
            if (biasdist < bestbiasd) {
                bestbiasd = biasdist
                bestbiaspos = i
            }
            val betafreq = freq[i] shr betashift
            freq[i] -= betafreq
            bias[i] += betafreq shl gammashift
        }
        freq[bestpos] += beta
        bias[bestpos] -= betagamma
        return bestbiaspos
    }
}
