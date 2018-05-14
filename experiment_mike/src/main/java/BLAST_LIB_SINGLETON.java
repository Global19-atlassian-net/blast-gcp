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

import java.io.StringWriter;
import java.io.PrintWriter;
import java.util.Map;
import java.util.List;
import java.util.LinkedList;
import java.util.concurrent.ConcurrentHashMap;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.Files;

import com.google.api.client.googleapis.auth.oauth2.GoogleCredential;
import com.google.api.client.googleapis.javanet.GoogleNetHttpTransport;
import com.google.api.client.http.HttpTransport;
import com.google.api.client.http.InputStreamContent;
import com.google.api.client.json.JsonFactory;
import com.google.api.client.json.jackson2.JacksonFactory;
import com.google.api.services.storage.Storage;
import com.google.api.services.storage.StorageScopes;
import com.google.api.services.storage.model.StorageObject;

import java.security.GeneralSecurityException;
import java.io.IOException;
import java.util.Collection;

import org.apache.log4j.Level;
import org.apache.log4j.LogManager;
import org.apache.log4j.Logger;
import org.apache.spark.SparkFiles;


class PART_INST
{
    public Integer part_id;
    public Long size;
    public Boolean prepared;

    public PART_INST( final BLAST_PARTITION part )
    {
        part_id = part.nr;
        size = 0L;
        prepared = false;
    }

    private static Storage buildStorageService() throws GeneralSecurityException, IOException
    {
        HttpTransport transport = GoogleNetHttpTransport.newTrustedTransport();
        JsonFactory jsonFactory = new JacksonFactory();
        GoogleCredential credential = GoogleCredential.getApplicationDefault( transport, jsonFactory );

        if ( credential.createScopedRequired() )
        {
            Collection<String> scopes = StorageScopes.all();
            credential = credential.createScoped( scopes );
        }
        return new Storage.Builder( transport, jsonFactory, credential )
            .setApplicationName("BLASTJNI")
            .build();
    }


    public Boolean prepare( final BLAST_PARTITION part, final BLAST_SETTINGS settings )

    {
        Logger logger = LogManager.getLogger(BLAST_LIB_SINGLETON.class);
        logger.log(Level.INFO, "in prepare");
        if (settings == null) logger.log(Level.ERROR,"null settings");

        Boolean res = false;
        try
        {
            List< String > extensions = new LinkedList<>();
            extensions.add( "nhr" );
            extensions.add( "nin" );
            extensions.add( "nsq" );
logger.log(Level.INFO, extensions.toString());

            List< String > obj_names = new LinkedList<>();
            for ( String ext : extensions )
            {
                String fn = String.format( "%s.%s", part.db_spec, ext );
                File f = new File( fn );
                if ( !f.exists() || ( f.length() == 0 ) )
                    obj_names.add( String.format( "%s.%s", part.name, ext ) );
            }
logger.log(Level.INFO, "obj_names is " + obj_names.toString());
            if ( !obj_names.isEmpty() )
            {
                Storage storage = buildStorageService();
                if (storage == null) logger.log(Level.ERROR,"null storage");
                for ( String obj_name : obj_names )
                {
logger.log(Level.INFO, "getting " + obj_name);
if (settings.db_bucket ==null) logger.log(Level.ERROR, "null db_bucket");
logger.log(Level.INFO, "bucket is " + settings.db_bucket);
                    Storage.Objects.Get obj = storage.objects().get( settings.db_bucket, obj_name );
                    if ( obj != null )
                    {
                        obj.getMediaHttpDownloader().setDirectDownloadEnabled( true );

                        String dst_path, dst_fn;

                        if ( settings.flat_db_layout )
                            dst_path = settings.db_location;
                        else
                            dst_path = String.format( "%s/%s", settings.db_location, part.name );
logger.log(Level.INFO, "dst_path is " + dst_path);
                        dst_fn = String.format( "%s/%s", dst_path, obj_name );
logger.log(Level.INFO, "dst_fn is " + dst_fn);

                        Files.createDirectories( Paths.get( dst_path ) );

                        System.err.println(String.format( "'%s:%s' --> '%s'", settings.db_bucket, obj_name, dst_fn ) );

                        File f = new File( dst_fn );
                        FileOutputStream f_out = new FileOutputStream( f );

                        obj.executeMediaAndDownloadTo( f_out );

                        f_out.flush();
                        f_out.close();
                    }
                }
                res = true;
            }
            else
                res = true;
            if ( size == 0L )
            {
                for ( String ext : extensions )
                {
                    String fn = String.format( "%s.%s", part.db_spec, ext );
                    File f = new File( fn );
                    size += f.length();
                }
            }
        }
        catch( Exception e )
        {
            System.err.println(String.format( "EXCEPTION gs: %s", e ) );
            StringWriter writer = new StringWriter();
            PrintWriter printWriter = new PrintWriter( writer );
            e.printStackTrace( printWriter );
            printWriter.flush();

            String stackTrace = writer.toString();
            logger.log(Level.ERROR,stackTrace);
            logger.log(Level.INFO, String.format( "EXCEPTION gs: %s", e ) );
        }
        return res;
    }
}

class BLAST_LIB_SINGLETON
{
    // let us have a map of PARTITION-ID to BLAST_LIB
    private static Map< Integer, PART_INST > parts = new ConcurrentHashMap<>();
    private static BLAST_LIB blaster = new BLAST_LIB();

    /* this ensures that nobody can make an instance of this class, but the class itself */
    private BLAST_LIB_SINGLETON()
    {
    }

    private static PART_INST getPartInst( final BLAST_PARTITION part )
    {
        if ( !parts.containsKey( part.nr ) )
            parts.put( part.nr, new PART_INST( part ) );
        return parts.get( part.nr );
    }

    public static BLAST_PARTITION prepare( final BLAST_PARTITION part, final BLAST_SETTINGS settings )
    {
        PART_INST p_inst = getPartInst( part );
        if ( p_inst != null )
        {
            if ( !p_inst.prepared )
                p_inst.prepared = p_inst.prepare( part, settings );
            
            return part.prepare();
        }
        return null;
    }

    public static BLAST_LIB get_lib( final BLAST_PARTITION part, final BLAST_SETTINGS settings )
    {
        PART_INST p_inst = getPartInst( part );
        if ( p_inst != null )
        {
            if ( !p_inst.prepared )
                p_inst.prepared = p_inst.prepare( part, settings );
            return blaster;
        }
        return null;
    }

    public static Long get_size( final BLAST_PARTITION part )
    {
        PART_INST p_inst = getPartInst( part );
        if ( p_inst != null )
            return p_inst.size;
        return 0L;
    }

}

