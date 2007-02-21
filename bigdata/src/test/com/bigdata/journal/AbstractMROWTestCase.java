/**

The Notice below must appear in each file of the Source Code of any
copy you distribute of the Licensed Product.  Contributors to any
Modifications may add their own copyright notices to identify their
own contributions.

License:

The contents of this file are subject to the CognitiveWeb Open Source
License Version 1.1 (the License).  You may not copy or use this file,
in either source code or executable form, except in compliance with
the License.  You may obtain a copy of the License from

  http://www.CognitiveWeb.org/legal/license/

Software distributed under the License is distributed on an AS IS
basis, WITHOUT WARRANTY OF ANY KIND, either express or implied.  See
the License for the specific language governing rights and limitations
under the License.

Copyrights:

Portions created by or assigned to CognitiveWeb are Copyright
(c) 2003-2003 CognitiveWeb.  All Rights Reserved.  Contact
information for CognitiveWeb is available at

  http://www.CognitiveWeb.org

Portions Copyright (c) 2002-2003 Bryan Thompson.

Acknowledgements:

Special thanks to the developers of the Jabber Open Source License 1.0
(JOSL), from which this License was derived.  This License contains
terms that differ from JOSL.

Special thanks to the CognitiveWeb Open Source Contributors for their
suggestions and support of the Cognitive Web.

Modifications:

*/
/*
 * Created on Feb 20, 2007
 */

package com.bigdata.journal;

import java.io.File;
import java.nio.ByteBuffer;
import java.nio.MappedByteBuffer;
import java.util.Collection;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Properties;
import java.util.Random;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import com.bigdata.rawstore.Addr;
import com.bigdata.util.concurrent.DaemonThreadFactory;

/**
 * Test suite for MROW (Multiple Readers, One Writer) support.
 * <p>
 * Supporting MROW is easy for a fully buffered implementation since it need
 * only use a read-only view for readers. If the implementation is not fully
 * buffered, e.g., {@link DiskOnlyStrategy}, then it needs to serialize reads
 * that are not buffered. The exception as always is the
 * {@link MappedBufferStrategy} - since this uses the nio
 * {@link MappedByteBuffer} it supports concurrent readers using the same
 * approach as a fully buffered strategy even though data may not always reside
 * in memory.
 * 
 * @todo This test suite could also be used to tune AIO (asynchronous IO)
 *       support for the {@link DirectBufferStrategy} and the
 *       {@link DiskOnlyStrategy}.
 * 
 * @author <a href="mailto:thompsonbry@users.sourceforge.net">Bryan Thompson</a>
 * @version $Id$
 */
abstract public class AbstractMROWTestCase extends AbstractBufferStrategyTestCase {

    /**
     * 
     */
    public AbstractMROWTestCase() {
    }

    /**
     * @param name
     */
    public AbstractMROWTestCase(String name) {
        super(name);
    }

    /**
     * Correctness/stress test verifies that the implementation supports
     * Multiple Readers One Writer (MROW).
     */
    public void test_mrow() throws Exception {

        IBufferStrategy store = ((Journal)getStore()).getBufferStrategy();

        final long timeout = 5;
        
        final int nclients = 20;
        
        final int nwrites = 10000;

        final int writeDelayMillis = 1;
        
        final int ntrials = 10000;
        
        final int reclen = 128;
        
        final int nreads = 100;
        
        doMROWTest(store, nwrites, writeDelayMillis, timeout,
                nclients, ntrials, reclen, nreads);
        
    }

    /**
     * A correctness/stress/performance test with a pool of concurrent clients
     * designed to verify MROW operations. If the store passes these tests, then
     * {@link StressTestConcurrent} is designed to reveal concurrency problems
     * in the higher level data structures (transaction process and especially
     * the indices).
     * 
     * @param store
     *            The store.
     * 
     * @param nwrites
     *            The #of records to write.
     * 
     * @param writeDelayMillis
     *            The #of milliseconds delay between writes.
     * 
     * @param timeout
     *            The timeout (seconds).
     * 
     * @param nclients
     *            The #of concurrent clients.
     * 
     * @param ntrials
     *            The #of distinct client trials to execute.
     * 
     * @param reclen
     *            The length of the random byte[] records used in the
     *            operations.
     * 
     * @param nreads
     *            The #of operations to be performed in each transaction.
     */
    static public void doMROWTest(IBufferStrategy store,
            int nwrites, long writeDelayMillis, long timeout, int nclients,
            int ntrials, int reclen, int nreads) 
        throws Exception
    {

        // A single-threaded writer.
        ExecutorService writerExecutor = Executors
                .newSingleThreadExecutor(DaemonThreadFactory
                        .defaultThreadFactory());

        WriterTask writerTask = new WriterTask(store, reclen, nwrites,
                writeDelayMillis);

        /*
         * Pre-write 25% of the records so that clients have something to
         * choose from when they start running.
         */
        final int npreWrites = nwrites/4;
        for( int i=0; i<npreWrites; i++) {
            
            // write a single record.
            writerTask.write();
            
        }
        System.err.println("Pre-wrote "+npreWrites+" records");
        
        // start the writer.
        writerExecutor.submit(writerTask);
        
        // Concurrent readers.
        ExecutorService readerExecutor = Executors.newFixedThreadPool(
                nclients, DaemonThreadFactory.defaultThreadFactory());

        // Setup readers queue.
        Collection<Callable<Long>> tasks = new HashSet<Callable<Long>>(); 
        
        for(int i=0; i<ntrials; i++) {
            
            tasks.add(new ReaderTask(store, writerTask, nreads));
            
        }

        /*
         * Run the M trials on N clients.
         */
        
        final long begin = System.currentTimeMillis();
        
        // start readers.
        List<Future<Long>> results = readerExecutor.invokeAll(tasks, timeout,
                TimeUnit.SECONDS);

        final long elapsed = System.currentTimeMillis() - begin;
        
        // force the writer to terminate.
        writerExecutor.shutdownNow();
        
        // force the reads to terminate.
        readerExecutor.shutdownNow();

        if(!writerExecutor.awaitTermination(1, TimeUnit.SECONDS)) {
        
            System.err.println("Writer did not terminate.");
        
        }
        
        if (!readerExecutor.awaitTermination(1, TimeUnit.SECONDS)) {

            /*
             * Note: if readers do not terminate within the timeout then an
             * IOException MAY be reported by disk-backed stores if the store is
             * closed while readers are still attempting to resolve records on
             * disk.
             */
            System.err.println("Reader(s) did not terminate.");

        }
        
        // #of records actually written.
        final int nwritten = writerTask.nrecs;
        
        Iterator<Future<Long>> itr = results.iterator();
        
        int nok = 0; // #of trials that successfully committed.
        int ncancelled = 0; // #of trials that did not complete in time.
        int nerr = 0;
        Throwable[] errors = new Throwable[ntrials];
        
        while(itr.hasNext()) {

            Future<Long> future = itr.next();
            
            if(future.isCancelled()) {
                
                ncancelled++;
                
                continue;
                
            }

            try {

                future.get(); // ignore the return (always zero).
                
                nok++;
                
            } catch(ExecutionException ex ) {

                System.err.println("Not expecting: "+ex);
                errors[nerr++] = ex.getCause();
                
            }
            
        }
        
        System.err.println("mode=" + store.getBufferMode() + ", #clients="
                + nclients + ", ntrials=" + ntrials + ", nok=" + nok
                + ", ncancelled=" + ncancelled + ", nerrors=" + nerr + " in "
                + elapsed + "ms (" + nok * 1000 / elapsed
                + " reads per second); nwritten=" + nwritten);
       
    }

    /**
     * A ground truth record as generated by a {@link WriterTask}.
     * 
     * @author <a href="mailto:thompsonbry@users.sourceforge.net">Bryan Thompson</a>
     * @version $Id$
     */
    public static class Record {
        public final long addr;
        public final byte[] data;
        public Record(long addr, byte[] data) {
            assert addr != 0L;
            assert data != null;
            this.addr = addr;
            this.data = data;
        }
    };
    
    /**
     * Run a writer.
     * <p>
     * The writer exposes state to the readers so that they can perform reads on
     * written records and so that the can validate those reads against ground
     * truth.
     */
    public static class WriterTask implements Callable<Integer> {

        private final IBufferStrategy store;
        private final int reclen;
        private final int nwrites;
        private final long writeDelayMillis;
        
        /**
         * The #of records in {@link #records}.
         */
        private volatile int nrecs = 0;
        
        /**
         * The ground truth data written so far.
         */
        private volatile Record[] records;
        
        final Random r = new Random();
        
        /**
         * Returns random data that will fit in N bytes. N is choosen randomly in
         * 1:<i>reclen</i>.
         * 
         * @return A new {@link ByteBuffer} wrapping a new <code>byte[]</code> of
         *         random length and having random contents.
         */
        private ByteBuffer getRandomData() {
            
            final int nbytes = r.nextInt(reclen) + 1;
            
            byte[] bytes = new byte[nbytes];
            
            r.nextBytes(bytes);
            
            return ByteBuffer.wrap(bytes);
            
        }

        public WriterTask(IBufferStrategy store, int reclen, int nwrites, long writeDelayMillis) {

            this.store = store;
            
            this.reclen = reclen;
            
            this.nwrites = nwrites;
            
            this.writeDelayMillis = writeDelayMillis;
            
            this.records = new Record[nwrites];
            
        }

        /**
         * Return a randomly choosen ground truth record. 
         */
        public Record getRandomGroundTruthRecord() {
            
            int index = r.nextInt(nrecs);
            
            return records[ index ];
            
        }

        /**
         * Writes any remaining records (starts from nrecs and runs to nwrites
         * so we can pre-write some records first).
         * 
         * @return The #of records written.
         */
        public Integer call() throws Exception {

            for (int i = nrecs; i < nwrites; i++) {

                write();
                
                /*
                 * Note: it is difficult to get this task to yield such that a
                 * large #of records are written, but not before the readers
                 * even get a chance to start executing. You may have to adjust
                 * this by hand for different JVM/OS combinations!
                 */
                Thread.sleep(0, 1);
                        
//                Thread.yield();
                
//                Thread.sleep(writeDelayMillis,writeDelayNanos);
//                long begin = System.nanoTime();
//                long elapsed = 0L;
//                while(elapsed<1000) {
//
//                    Thread.yield();
//                    elapsed = System.nanoTime() - begin;
//                    
//                }

            }

            System.err.println("Writer done: nwritten="+nrecs);
            
            return nrecs;
        
        }
        
        /**
         * Write a random record and record it in {@link #records}.
         */
        public void write() {

            ByteBuffer data = getRandomData();
            
            final long addr = store.write(data);

            records[nrecs] = new Record(addr, data.array());

            nrecs++;

        }
        
    }
    
    /**
     * Run a reader.
     */
    public static class ReaderTask implements Callable<Long> {

        private final IBufferStrategy store;
        private final WriterTask writer;
        private final int nops;
        
        final Random r = new Random();
        
        /**
         * 
         * @param store
         * @param writer
         * @param nwrites #of reads to perform.
         */
        public ReaderTask(IBufferStrategy store, WriterTask writer, int nops) {

            this.store = store;
            
            this.writer = writer;
            
            this.nops = nops;
            
        }

        /**
         * Executes random reads and validates against ground truth.
         */
        public Long call() throws Exception {
            
            // Random reads.

            for (int i = 0; i < nops; i++) {

//                Thread.yield();
    
                Record record = writer.getRandomGroundTruthRecord();

                ByteBuffer buf;
                
                if (r.nextInt(100) > 30) {

                    buf = store.read(record.addr);

                } else {

                    buf = ByteBuffer.allocate(Addr.getByteCount(record.addr));

                    buf = store.read(record.addr);

                }
                
                assertEquals(record.data, buf);

            }

            return 0L;
            
        }
        
    }

    /**
     * Correctness/stress/performance test for MROW behavior.
     */
    public static void main(String[] args) throws Exception {
        
        // timeout in seconds.
        final long timeout = 10;
        
        final int nclients = 20;
        
        final int nwrites = 10000;

        final int writeDelayMillis = 1;
        
        final int ntrials = 100000;
        
        final int reclen = 1024;
        
        final int nreads = 100;
        
        Properties properties = new Properties();
        
//      properties.setProperty(Options.USE_DIRECT_BUFFERS,"false");
//      properties.setProperty(Options.BUFFER_MODE, BufferMode.Transient.toString());

//      properties.setProperty(Options.USE_DIRECT_BUFFERS,"true");
//      properties.setProperty(Options.BUFFER_MODE, BufferMode.Transient.toString());

//      properties.setProperty(Options.USE_DIRECT_BUFFERS,"false");
//      properties.setProperty(Options.BUFFER_MODE, BufferMode.Direct.toString());
        
      properties.setProperty(Options.USE_DIRECT_BUFFERS,"true");
      properties.setProperty(Options.BUFFER_MODE, BufferMode.Direct.toString());

//      properties.setProperty(Options.BUFFER_MODE, BufferMode.Mapped.toString());
        
//      properties.setProperty(Options.BUFFER_MODE, BufferMode.Disk.toString());

        properties.setProperty(Options.SEGMENT, "0");
        
        File file = File.createTempFile("bigdata", ".jnl");
        
        file.deleteOnExit();
        
        if(!file.delete()) fail("Could not remove temp file before test");
        
        properties.setProperty(Options.FILE, file.toString());
        
        Journal journal = new Journal(properties);
        
        doMROWTest(journal.getBufferStrategy(), nwrites,
                writeDelayMillis, timeout, nclients, ntrials, reclen, nreads);
        
        journal.shutdown();
        
    }

}
