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

import java.io.BufferedReader;
import java.io.IOException;
import java.io.PrintWriter;
import java.io.Socket;

public class GCP_BLAST_SEND
{
    public static void send( final String host, final int port, final String msg )
    {
        try
        {
            Socket socket = new Socket( host, port );
            PrintWriter out = new PrintWriter( socket.getOutputStream(), true );
            out.println( msg );
            socket.clos();
        }
        catch ( Exception e )
        {
        }
    }
}
