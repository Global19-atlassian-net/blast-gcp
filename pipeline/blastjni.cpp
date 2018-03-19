/* ===========================================================================
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

#include "BlastJNI.h"
#include <jni.h>

#include "blast4spark.hpp"
#include <algo/blast/api/blast_advprot_options.hpp>
#include <algo/blast/api/blast_exception.hpp>
#include <algo/blast/api/blast_nucl_options.hpp>
#include <algo/blast/api/blast_results.hpp>
#include <algo/blast/api/local_blast.hpp>
#include <algo/blast/api/objmgrfree_query_data.hpp>
#include <algo/blast/api/prelim_stage.hpp>
#include <algo/blast/core/blast_hspstream.h>
#include <ctype.h>
#include <ncbi_pch.hpp>
#include <objects/seq/Bioseq.hpp>
#include <objects/seq/Seq_data.hpp>
#include <objects/seqalign/Seq_align.hpp>
#include <objects/seqalign/Seq_align_set.hpp>
#include <objects/seqloc/Seq_id.hpp>
#include <objects/seqset/Bioseq_set.hpp>
#include <objects/seqset/Seq_entry.hpp>
#include <stdio.h>
#include <stdlib.h>
#include <string>
#include <sys/types.h>
#include <unistd.h>
#include <vector>

static void log(const char* msg)
{
    const bool debug = true;

    if (debug) {
        char fname[64];
        sprintf(fname, "/tmp/blastjni.%u.log", getpid());
        FILE* fout = fopen(fname, "a");
        if (!fout) return;
        fputs(msg, fout);
        fputc('\n', fout);
        fclose(fout);
    }
}

static void iterate_HSPs(BlastHSPList* hsp_list, const char* chunk_id,
                         const char* rid, std::vector<std::string>& vs)
{
    for (int i = 0; i < hsp_list->hspcnt; ++i) {
        const BlastHSP* hsp = hsp_list->hsp_array[i];
        char buf[256];
        sprintf(buf,
                "{"
                "\"chunk\": \"%s\", "
                "\"RID\": \"%s\", "
                "\"oid\": %d, "
                "\"score\": %d, "
                "\"qstart\": %d, "
                "\"qstop\": %d, "
                "\"sstart\": %d, "
                "\"sstop\": %d "
                "}\n",
                chunk_id, rid, hsp_list->oid, hsp->score, hsp->query.offset,
                hsp->query.end, hsp->subject.offset, hsp->subject.end);
        vs.push_back(buf);
    }
}

JNIEXPORT jobjectArray JNICALL Java_BlastJNI_prelim_1search(
    JNIEnv* env, jobject jobj, jstring dbenv, jstring rid, jstring query,
    jstring db, jstring params)
{
    char msg[256];
    log("Entered C++ Java_BlastJNI_prelim_1search");

    const char* cdbenv = env->GetStringUTFChars(dbenv, NULL);
    log(cdbenv);

    const char* crid = env->GetStringUTFChars(rid, NULL);
    log(crid);

    const char* cquery = env->GetStringUTFChars(query, NULL);
    log(cquery);

    const char* cdb = env->GetStringUTFChars(db, NULL);
    log(cdb);

    const char* cparams = env->GetStringUTFChars(params, NULL);
    log(cparams);

    const char* chunk_id = cdbenv;
    std::string squery(cquery);
    std::string sdb(cdb);
    std::string sparams(cparams);

    if (getenv("BLASTDB")) {
        sprintf(msg, "  $BLASTDB was    %s", getenv("BLASTDB"));
        log(msg);
    }

    if (setenv("BLASTDB", cdbenv, 1)) {
        sprintf(msg, "Couldn't setenv BLASTDB=%s, errno:%d", cdbenv, errno);
        log(msg);
    }

    if (getenv("BLASTDB")) {
        sprintf(msg, "  $BLASTDB is now %s", getenv("BLASTDB"));
        log(msg);
    }

    BlastHSPStream* hsp_stream
        = ncbi::blast::PrelimSearch(squery, sdb, sparams);

    std::vector<std::string> vs;

    // TODO: Exceptions: env->Throw(...)
    if (hsp_stream != NULL) {
        BlastHSPList* hsp_list = NULL;
        int status = BlastHSPStreamRead(hsp_stream, &hsp_list);
        sprintf(msg, "  BlastHSPStreamRead returned status = %d", status);
        log(msg);

        if (status == kBlastHSPStream_Error) {
            log("TODO: Exception");
            // TODO: Exception
        }

        while (status == kBlastHSPStream_Success && hsp_list != NULL) {
            sprintf(msg, "  %s - have hsp_list at %p", __func__, hsp_list);
            log(msg);
            iterate_HSPs(hsp_list, chunk_id, crid, vs);
            status = BlastHSPStreamRead(hsp_stream, &hsp_list);
            if (status == kBlastHSPStream_Error) {
                log("TODO: Exception");
                // TODO: Exception
            }
        }

        Blast_HSPListFree(hsp_list);
        BlastHSPStreamFree(hsp_stream);
    } else {
        log("NULL hsp_stream");
        // TODO: Exception
    }

    sprintf(msg, "  Have %lu elements", vs.size());
    log(msg);

    if (!vs.size()) {
        char buf[256];
        log("Empty hsp_list, emitting sentinel");
        sprintf(buf,
                "{"
                "\"chunk\": \"%s\", "
                "\"RID\": \"%s\", "
                "\"oid\": %d, "
                "\"score\": %d, "
                "\"qstart\": %d, "
                "\"qstop\": %d, "
                "\"sstart\": %d, "
                "\"sstop\": %d "
                "}\n",
                chunk_id, crid, -1, -1, -1, -1, -1, -1);
        vs.push_back(buf);
    }

    jobjectArray ret;
    ret = (jobjectArray)env->NewObjectArray(
        vs.size(), env->FindClass("java/lang/String"), NULL);

    for (size_t i = 0; i != vs.size(); ++i) {
        const char* buf = vs[i].data();
        env->SetObjectArrayElement(ret, i, env->NewStringUTF(buf));
    }

    env->ReleaseStringUTFChars(dbenv, cdbenv);
    env->ReleaseStringUTFChars(rid, crid);
    env->ReleaseStringUTFChars(query, cquery);
    env->ReleaseStringUTFChars(db, cdb);
    env->ReleaseStringUTFChars(params, cparams);
    log("Leaving C++ Java_BlastJNI_prelim_1search\n");
    return (ret);
}
JNIEXPORT jstring JNICALL Java_BlastJNI_traceback(JNIEnv*, jobject, jstring)
{
    return 0;
}
