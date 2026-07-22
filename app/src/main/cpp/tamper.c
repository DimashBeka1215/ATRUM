#include <stdint.h>
#include <string.h>

typedef struct {
    uint32_t state[8];
    uint64_t bitlen;
    uint8_t  data[64];
    uint32_t datalen;
} sha256_ctx;

#define ROTR(x,n) (((x) >> (n)) | ((x) << (32 - (n))))
#define CH(x,y,z)  (((x) & (y)) ^ (~(x) & (z)))
#define MAJ(x,y,z) (((x) & (y)) ^ ((x) & (z)) ^ ((y) & (z)))
#define EP0(x)  (ROTR(x,2)  ^ ROTR(x,13) ^ ROTR(x,22))
#define EP1(x)  (ROTR(x,6)  ^ ROTR(x,11) ^ ROTR(x,25))
#define SIG0(x) (ROTR(x,7)  ^ ROTR(x,18) ^ ((x) >> 3))
#define SIG1(x) (ROTR(x,17) ^ ROTR(x,19) ^ ((x) >> 10))

static const uint32_t K[64] = {
    0x428a2f98,0x71374491,0xb5c0fbcf,0xe9b5dba5,0x3956c25b,0x59f111f1,0x923f82a4,0xab1c5ed5,
    0xd807aa98,0x12835b01,0x243185be,0x550c7dc3,0x72be5d74,0x80deb1fe,0x9bdc06a7,0xc19bf174,
    0xe49b69c1,0xefbe4786,0x0fc19dc6,0x240ca1cc,0x2de92c6f,0x4a7484aa,0x5cb0a9dc,0x76f988da,
    0x983e5152,0xa831c66d,0xb00327c8,0xbf597fc7,0xc6e00bf3,0xd5a79147,0x06ca6351,0x14292967,
    0x27b70a85,0x2e1b2138,0x4d2c6dfc,0x53380d13,0x650a7354,0x766a0abb,0x81c2c92e,0x92722c85,
    0xa2bfe8a1,0xa81a664b,0xc24b8b70,0xc76c51a3,0xd192e819,0xd6990624,0xf40e3585,0x106aa070,
    0x19a4c116,0x1e376c08,0x2748774c,0x34b0bcb5,0x391c0cb3,0x4ed8aa4a,0x5b9cca4f,0x682e6ff3,
    0x748f82ee,0x78a5636f,0x84c87814,0x8cc70208,0x90befffa,0xa4506ceb,0xbef9a3f7,0xc67178f2
};

static void sha256_transform(sha256_ctx *c, const uint8_t *d) {
    uint32_t a,b,e,f,g,h,i,j,t1,t2,m[64];
    for (i=0,j=0;i<16;i++,j+=4)
        m[i]=(d[j]<<24)|(d[j+1]<<16)|(d[j+2]<<8)|(d[j+3]);
    for (;i<64;i++)
        m[i]=SIG1(m[i-2])+m[i-7]+SIG0(m[i-15])+m[i-16];
    a=c->state[0];b=c->state[1];e=c->state[2];f=c->state[3];
    g=c->state[4];h=c->state[5];i=c->state[6];j=c->state[7];
    {
        uint32_t A=a,B=b,C=e,D=f,E=g,F=h,G=i,H=j;
        for (int t=0;t<64;t++){
            t1=H+EP1(E)+CH(E,F,G)+K[t]+m[t];
            t2=EP0(A)+MAJ(A,B,C);
            H=G;G=F;F=E;E=D+t1;D=C;C=B;B=A;A=t1+t2;
        }
        c->state[0]+=A;c->state[1]+=B;c->state[2]+=C;c->state[3]+=D;
        c->state[4]+=E;c->state[5]+=F;c->state[6]+=G;c->state[7]+=H;
    }
}

static void sha256_init(sha256_ctx *c){
    c->datalen=0;c->bitlen=0;
    c->state[0]=0x6a09e667;c->state[1]=0xbb67ae85;c->state[2]=0x3c6ef372;c->state[3]=0xa54ff53a;
    c->state[4]=0x510e527f;c->state[5]=0x9b05688c;c->state[6]=0x1f83d9ab;c->state[7]=0x5be0cd19;
}

static void sha256_update(sha256_ctx *c, const uint8_t *d, size_t len){
    for (size_t i=0;i<len;i++){
        c->data[c->datalen++]=d[i];
        if (c->datalen==64){ sha256_transform(c,c->data); c->bitlen+=512; c->datalen=0; }
    }
}

static void sha256_final(sha256_ctx *c, uint8_t *out){
    uint32_t i=c->datalen;
    if (c->datalen<56){ c->data[i++]=0x80; while(i<56) c->data[i++]=0x00; }
    else { c->data[i++]=0x80; while(i<64) c->data[i++]=0x00; sha256_transform(c,c->data); memset(c->data,0,56); }
    c->bitlen+=(uint64_t)c->datalen*8;
    for (int k=7;k>=0;k--) c->data[56+(7-k)]=(uint8_t)(c->bitlen>>(k*8));
    sha256_transform(c,c->data);
    for (i=0;i<4;i++)
        for (int k=0;k<8;k++)
            out[i+k*4]=(uint8_t)((c->state[k]>>(24-i*8))&0xff);
}

static const uint8_t EXP_ENC[32] = {
    0x57,0x78,0x9a,0x6e,0x8d,0x1a,0x63,0xf0,0x56,0xaa,0x44,0x6d,0xc4,0x97,0x49,0x0b,
    0x94,0x23,0xfd,0x39,0x2e,0x69,0xa3,0xde,0xae,0xe3,0xd7,0xf0,0xe9,0x3f,0xa4,0xe8
};

int atrum_verify(const uint8_t *der, int len){
    if (der == 0 || len <= 0) return -1;
    sha256_ctx c;
    uint8_t digest[32];
    sha256_init(&c);
    sha256_update(&c, der, (size_t)len);
    sha256_final(&c, digest);
    uint8_t diff = 0;
    for (int i=0;i<32;i++)
        diff |= (uint8_t)(digest[i] ^ (EXP_ENC[i] ^ 0x5B));
    return diff == 0 ? 1 : 0;
}

#if defined(__ANDROID__)
#include <jni.h>

JNIEXPORT jint JNICALL
Java_com_atrum_chat_NativeCodec_nativeVerify(JNIEnv *env, jclass clazz, jbyteArray der){
    if (der == 0) return -1;
    jsize len = (*env)->GetArrayLength(env, der);
    if (len <= 0) return -1;
    jbyte *buf = (*env)->GetByteArrayElements(env, der, 0);
    if (buf == 0) return -1;
    int r = atrum_verify((const uint8_t*)buf, (int)len);
    (*env)->ReleaseByteArrayElements(env, der, buf, JNI_ABORT);
    return (jint)r;
}
#endif

#ifdef ATRUM_TEST
#include <stdio.h>
#include <stdlib.h>
int main(int argc, char **argv){
    if (argc < 2){ printf("usage: %s <der-file>\n", argv[0]); return 2; }
    FILE *f = fopen(argv[1], "rb");
    if (!f){ printf("no file\n"); return 2; }
    fseek(f,0,SEEK_END); long n=ftell(f); fseek(f,0,SEEK_SET);
    uint8_t *buf = (uint8_t*)malloc(n);
    fread(buf,1,n,f); fclose(f);
    int r = atrum_verify(buf, (int)n);
    printf("atrum_verify -> %d  (%s)\n", r, r==1?"MATCH":r==0?"MISMATCH":"ERROR");
    free(buf);
    return r==1?0:1;
}
#endif
