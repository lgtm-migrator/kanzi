/*
Copyright 2011-2017 Frederic Langlet
Licensed under the Apache License, Version 2.0 (the "License");
you may not use this file except in compliance with the License.
you may obtain a copy of the License at

                http://www.apache.org/licenses/LICENSE-2.0

Unless required by applicable law or agreed to in writing, software
distributed under the License is distributed on an "AS IS" BASIS,
WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
See the License for the specific language governing permissions and
limitations under the License.
*/

package kanzi.entropy;

import java.util.Arrays;
import kanzi.OutputBitStream;
import kanzi.BitStreamException;
import kanzi.EntropyEncoder;
import kanzi.Global;


// Implementation of a static Huffman encoder.
// Uses in place generation of canonical codes instead of a tree
public class HuffmanEncoder implements EntropyEncoder
{
   private final OutputBitStream bs;
   private final int[] freqs;
   private final int[] codes;
   private final int[] alphabet;
   private final int[] sranks;  // sorted ranks
   private final int[] buffer;  // temporary data
   private final short[] sizes; 
   private final int chunkSize;
   private int maxCodeLen;


   public HuffmanEncoder(OutputBitStream bitstream) throws BitStreamException
   {
      this(bitstream, HuffmanCommon.MAX_CHUNK_SIZE);
   }


    // The chunk size indicates how many bytes are encoded (per block) before
    // resetting the frequency stats. 
   public HuffmanEncoder(OutputBitStream bitstream, int chunkSize) throws BitStreamException
   {
      if (bitstream == null)
         throw new NullPointerException("Huffman codec: Invalid null bitstream parameter");

      if (chunkSize < 1024)
         throw new IllegalArgumentException("Huffman codec: The chunk size must be at least 1024");

      if (chunkSize > HuffmanCommon.MAX_CHUNK_SIZE)
         throw new IllegalArgumentException("Huffman codec: The chunk size must be at most "+HuffmanCommon.MAX_CHUNK_SIZE);

      this.bs = bitstream;
      this.freqs = new int[256];
      this.sizes = new short[256];
      this.alphabet = new int[256];
      this.sranks = new int[256];
      this.buffer = new int[256];
      this.codes = new int[256];
      this.chunkSize = chunkSize;

      // Default frequencies, sizes and codes
      for (int i=0; i<256; i++)
      {
         this.freqs[i] = 1;
         this.sizes[i] = 8;
         this.codes[i] = i;
      }
   }

    
   // Rebuild Huffman codes
   private int updateFrequencies(int[] frequencies) throws BitStreamException
   {
      if ((frequencies == null) || (frequencies.length != 256))
         return -1;

      int count = 0;

      for (int i=0; i<256; i++)
      {
         this.sizes[i] = 0;
         this.codes[i] = 0;

         if (frequencies[i] > 0)
            this.alphabet[count++] = i;
      }

      EntropyUtils.encodeAlphabet(this.bs, this.alphabet, count);

      // Transmit code lengths only, frequencies and codes do not matter
      // Unary encode the length difference
      this.computeCodeLengths(frequencies, count);     
      ExpGolombEncoder egenc = new ExpGolombEncoder(this.bs, true);
      short prevSize = 2;

      for (int i=0; i<count; i++)
      {
         final short currSize = this.sizes[this.alphabet[i]];
         egenc.encodeByte((byte) (currSize - prevSize));
         prevSize = currSize;
      }

      // Create canonical codes 
      if (HuffmanCommon.generateCanonicalCodes(this.sizes, this.codes, this.alphabet, count) < 0)
         throw new IllegalArgumentException("Could not generate Huffman codes: max code length (" +
            HuffmanCommon.MAX_SYMBOL_SIZE + " bits) exceeded");

      // Pack size and code (size <= MAX_SYMBOL_SIZE bits)
      for (int i=0; i<count; i++)
      {
         final int r = this.alphabet[i];
         this.codes[r] |= (this.sizes[r]<<24);           
      }

      return count;
   }


   private void computeCodeLengths(int[] frequencies, int count) 
   {  
      if (count == 1)
      {
         this.sranks[0] = this.alphabet[0];
         this.sizes[this.alphabet[0]] = 1;
         return;
      }

      // Sort ranks by increasing frequencies (first key) and increasing value (second key)
      for (int i=0; i<count; i++)
         this.sranks[i] = (frequencies[this.alphabet[i]]<<8) | this.alphabet[i];

      Arrays.sort(this.sranks, 0, count);

      for (int i=0; i<count; i++)               
      {
         this.buffer[i] = this.sranks[i] >>> 8;
         this.sranks[i] &= 0xFF;
      }
      
      // See [In-Place Calculation of Minimum-Redundancy Codes]
      // by Alistair Moffat & Jyrki Katajainen
      computeInPlaceSizesPhase1(this.buffer, count);
      computeInPlaceSizesPhase2(this.buffer, count);
      this.maxCodeLen = 0;

      for (int i=0; i<count; i++) 
      {
         short codeLen = (short) this.buffer[i];

         if (codeLen == 0)
            throw new IllegalArgumentException("Could not generate Huffman codes: invalid code length 0");

         if (codeLen > HuffmanCommon.MAX_SYMBOL_SIZE)
            throw new IllegalArgumentException("Could not generate Huffman codes: max code length (" +
               HuffmanCommon.MAX_SYMBOL_SIZE + " bits) exceeded");

         if (this.maxCodeLen < codeLen)
            this.maxCodeLen = codeLen;
         
         this.sizes[this.sranks[i]] = codeLen;
      }
   }
    
    
   static void computeInPlaceSizesPhase1(int[] data, int n) 
   {
      for (int s=0, r=0, t=0; t<n-1; t++) 
      {
         int sum = 0;

         for (int i=0; i<2; i++) 
         {
            if ((s>=n) || ((r<t) && (data[r]<data[s]))) 
            {
               sum += data[r];
               data[r] = t;
               r++;
               continue;
            }

            sum += data[s];

            if (s > t) 
               data[s] = 0;

            s++;
         }

         data[t] = sum;
      }
   }

    
   static void computeInPlaceSizesPhase2(int[] data, int n) 
   {
      int levelTop = n - 2; //root
      int depth = 1;
      int i = n;
      int totalNodesAtLevel =  2;

      while (i > 0) 
      {
         int k = levelTop;

         while ((k>0) && (data[k-1]>=levelTop))
            k--;

         final int internalNodesAtLevel = levelTop - k;
         final int leavesAtLevel = totalNodesAtLevel - internalNodesAtLevel;

         for (int j=0; j<leavesAtLevel; j++)
            data[--i] = depth;

         totalNodesAtLevel = internalNodesAtLevel << 1;
         levelTop = k;
         depth++;
      }
   }


   // Dynamically compute the frequencies for every chunk of data in the block   
   @Override
   public int encode(byte[] block, int blkptr, int count)
   {
      if ((block == null) || (blkptr+count > block.length) || (blkptr < 0) || (count < 0))
         return -1;

      if (count == 0)
         return 0;

      final int end = blkptr + count;
      int startChunk = blkptr;

      while (startChunk < end)
      {
         // Update frequencies and rebuild Huffman codes
         final int endChunk = (startChunk+this.chunkSize < end) ? startChunk+this.chunkSize : end;
         Global.computeHistogramOrder0(block, startChunk, endChunk, this.freqs, false);
         this.updateFrequencies(this.freqs);
         final OutputBitStream bitstream = this.bs;                 
         final int[] c = this.codes;
         final int endChunk4 = ((endChunk-startChunk) & -4) + startChunk;

         for (int i=startChunk; i<endChunk4; i+=4)
         {
            // Pack 4 codes into 1 long
            final int code1 = c[block[i]&0xFF];
            final int codeLen1 = code1 >>> 24;
            final int code2 = c[block[i+1]&0xFF];
            final int codeLen2 = code2 >>> 24;
            final int code3 = c[block[i+2]&0xFF];
            final int codeLen3 = code3 >>> 24;
            final int code4 = c[block[i+3]&0xFF];
            final int codeLen4 = code4 >>> 24;
            final long st = ((((long) code1)&0xFFFF)<<(codeLen2+codeLen3+codeLen4) | 
                ((((long) code2)&((1<<codeLen2)-1))<<(codeLen3+codeLen4))| 
                ((((long) code3)&((1<<codeLen3)-1))<<codeLen4)| 
                  ((long) code4)&((1<<codeLen4)-1)); 
            bitstream.writeBits(st, codeLen1+codeLen2+codeLen3+codeLen4);
         }

         for (int i=endChunk4; i<endChunk; i++)
         {
            final int code = c[block[i]&0xFF];
            bitstream.writeBits(code, code>>>24);
         }

         startChunk = endChunk;
      }

      return count;
   }


   @Override
   public OutputBitStream getBitStream()
   {
      return this.bs;
   }

   
   @Override
   public void dispose() 
   {
   }
}