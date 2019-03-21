/*===========================================================================
*
*                            PUBLIC DOMAIN NOTICE
*               National Center for Biotechnology Information
*
*  This software/database is a "United States Government Work" under the
*  terms of the United States Copyright Act.  It was written as part of
*  the author's official duties as a United States Government employee and
*  thus cannot be copyrighted.  This software/database is freely available
*  to the public for use. The National Library of Medicine and the U.S.
*  Government have not placed any restriction on its use or reproduction.
*
*  Although all reasonable efforts have been taken to ensure the accuracy
*  and reliability of the software and data, the NLM and the U.S.
*  Government do not and cannot warrant the performance or results that
*  may be obtained by using this software or data. The NLM and the U.S.
*  Government disclaim all warranties, express or implied, including
*  warranties of performance, merchantability or fitness for any particular
*  purpose.
*
*  Please cite the author in any work or product based on this material.
*
* ===========================================================================
*
*/

package gov.nih.nlm.ncbi.blastjni;

import java.util.List;
import java.util.ArrayList;
import java.util.Collections;

import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;

import org.apache.log4j.LogManager;
import org.apache.log4j.Logger;

/**
 * Context of the Application :
 * - stores the Application-settings
 * - owns a atomic Boolean for the running state
 * - owns a thread-safe queue for commands
 * - owns a thread-safe queue for requests
 * - owns a list of Request-Lists
 * @see        BC_SETTINGS
*/
public class BC_CONTEXT
{
    public final BC_SETTINGS settings;
    private final AtomicBoolean running;
    private final ConcurrentLinkedQueue< BC_COMMAND > command_queue;
    private final ConcurrentLinkedQueue< BC_REQUEST > request_queue;
    private final BC_LISTS list_manager;
    private BC_JOBS jobs;
    private final List< String > history;
    private final Logger logger;

/**
 * create instance of BC_CONTEXT from settings
 * - store reference to settings
 * - create private queues and BC_LISTS-instance
 *
 * @param a_settings application-settings
 * @see        BC_SETTINGS
*/
    public BC_CONTEXT( final BC_SETTINGS a_settings )
    {
        settings = a_settings;
        running = new AtomicBoolean( true );
        command_queue = new ConcurrentLinkedQueue<>();
        request_queue = new ConcurrentLinkedQueue<>();
        list_manager = new BC_LISTS( this );
        jobs = null;
        history = Collections.synchronizedList( new ArrayList< String >() );
        logger = LogManager.getLogger( BC_CONTEXT.class );
    }

/**
 * set the jobs-manager after creation
 *
 * @param   reference to jobs-manager
*/
    public void set_jobs( BC_JOBS a_jobs ) { jobs = a_jobs; }

/**
 * test if the request-queue has space for at least one more request
 *
 * @return     does the request-queue has space for at least one more request ?
*/
    public int can_take() { return ( settings.req_max_backlog - request_queue.size() ); }

/**
 * test if the application is still in running mode
 *
 * @return     is the application still in running mode ?
*/
    public boolean is_running() { return running.get(); }

/**
 * stop the application
*/
    public void stop() { running.set( false ); }

/**
 * stop the processing of pending lists
*/
    public void stop_lists() { list_manager.stop(); }

/**
 * put a command into the internal command-queue
 *
 * @param command the command to be put into the queue
*/
    public void push_command( final String command )
    {
        if ( !command.isEmpty() )
        {
            if ( !command.startsWith( "H" ) && !command.startsWith( "E" ) )
                history.add( command );

            command_queue.offer( new BC_COMMAND( command ) );
        }
    }

/**
 * return a history string
 *
 * @param idx   index in the history buffer
 * @return String from history buffer
*/
    public String get_cmd_history( int idx )
    {
        String res = "";
        if ( idx < history.size() )
            res = history.get( idx );
        return res;
    }

/**
 * return how many history entries we have
 *
 * @return how many history entries ?
*/
    public int get_cmd_history()
    {
        return history.size();
    }

/**
 * get a command from the internal command-queue
 *
 * @return a BC_COMMAND instance or null if the queue is empty
*/
    public BC_COMMAND pull_cmd() { return command_queue.poll(); }

/**
 * return reference to internally store settings
 *
 * @return application settings
*/
    public BC_SETTINGS get_settings() { return settings; }

/**
 * test if a request is already in the internal queue
 *
 * @param request BC_REQUEST-instance to be tested
 * @return        is the BC_REQUEST-instance already in the internal queue ?
*/
    public boolean contains( final BC_REQUEST request )
    {
        boolean res = request_queue.contains( request );
        //if ( !res ) res = is_a_running_id( request.id ); /* we will need this protection again */
        return res;
    }

/**
 * put a BC_REQUEST-instance into the internal request-queue
 *
 * @param request  BC_REQUEST-instance to be added
 * @param ps       stream to be used for error messages, can be null
 * @return         was the request successfully added ?
*/
    public boolean add_request( final BC_REQUEST request )
    {
        boolean res = !contains( request );
        if ( res )
        {
            request_queue.offer( request );

            if ( settings.debug.req_added )
                logger.info( String.format( "REQUEST '%s' added to queue", request.id ) );
        }
        else
        {
            logger.info( String.format( "REQUEST '%s' rejected: already queued", request.id ) );
        }
        return res;
    }

/**
 * put a String, containing a json-encoded BC_REQUEST into the internal request-queue
 *
 * @param req_string String containing a json-encoded BC_REQUEST
 * @param ps         stream to be used for error messages, can be null
 * @return           was the request successfully added ?
*/
    public boolean add_request_string( final String req_string )
    {
        boolean res = false;
        if ( can_take() > 0 )
        {
            BC_REQUEST request = BC_REQUEST_READER.parse_from_string( req_string );
            if ( request != null )
                res = add_request( request );
            else
                logger.info( String.format( "invalid request '%s'", req_string ) );
        }
        return res;
    }

/**
 * put a file, containing a json-encoded BC_REQUEST into the internal request-queue
 *
 * @param filename name of file containing a json-encoded BC_REQUEST
 * @param ps       stream to be used for error messages ( cannot be null )
 * @return         1..done, 0..not done, -1..request invalid
*/
    public int add_request_file( final String filename)
    {
        int res = 0;
        if ( can_take() > 0 )
        {
            BC_REQUEST request = BC_REQUEST_READER.parse_from_file( filename );
            if ( request != null )
            {
                if ( add_request( request ) )
                    res = 1;
            }
            else
            {
                logger.info( String.format( "invalid request in file '%s'", filename ) );
                res = -1;
            }
        }
        return res;
    }

/**
 * get a BC_REQUEST-instance from the internal request-queue
 *
 * @return         a valid BC_REQUEST-instance or null
*/
    public BC_REQUEST get_request()
    {
        return request_queue.poll();
    }

/**
 * add a list of requests to the internal list-manager
 *
 * @param filename name of file containing a list of request-files
 * @param ps       stream to be used for error messages ( cannot be null )
 * @param limit    limit of entries to use
*/
    public void addRequestList( final String filename, int limit )
    {
        list_manager.addFile( filename, limit );
    }

/**
 * add a bucket to the internal list-manager
 *
 * @param bucket_url url of bucket containing request-files
 * @param ps         stream to be used for error messages ( cannot be null )
 * @param limit      limit of request-files to use
*/
    public void addRequestBucket( final String bucket_url, int limit )
    {
        list_manager.addBucket( bucket_url, limit );
    }

/**
 * print info about context...
 *
 * @param ps         stream to be used for error messages ( cannot be null )
*/
    public void print_info()
    {
        logger.info( String.format( "request-queue: %d of %d\n",
                    request_queue.size(), settings.req_max_backlog ) );

        int n = ( jobs != null ) ? jobs.active() : 0;
        logger.info( String.format( "jobs active  : %d of %d\n",
                    n, settings.parallel_jobs ) );
    }

/**
 * print info about context...
 *
 * @param ps         stream to be used for error messages ( cannot be null )
*/
    public void wait_for_empty( int minutes_to_wait )
    {
        boolean done = false;
        long endtime = 0;
        if ( minutes_to_wait > 0 )
        {
            endtime = System.currentTimeMillis() + ( 1000 * 60 * minutes_to_wait );
        }

        while ( !done )
        {
            done = ( ( request_queue.size() == 0 ) && ( jobs.active() == 0 ) );
            if ( !done && endtime > 0 )
                done = ( System.currentTimeMillis() > endtime );
            if ( !done )
            {
                try
                {
                    Thread.sleep( 500 );
                }
                catch ( InterruptedException e ) { }
            }
        }
    }

/**
 * wait for all list-threads owned by the list-manager to finish
 *
*/
    public void join()
    {
        list_manager.join();
    }
}

