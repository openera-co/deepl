import csv,re,sys
S=sys.argv[1]
ref={}
for r in csv.reader(open(S+"/test.tsv"),delimiter="\t",quoting=csv.QUOTE_NONE): ref[r[1]]=r[2]
def norm(t): t=t.lower().replace("’","'"); t=re.sub(r"[^\w' -]"," ",t); return t.split()
def ed(a,b):
  d=list(range(len(b)+1))
  for i,x in enumerate(a,1):
    p,d[0]=d[0],i
    for j,y in enumerate(b,1): p,d[j]=d[j],min(d[j]+1,d[j-1]+1,p+(x!=y))
  return d[-1]
for f in sys.argv[2:]:
  e=n=0
  for l in open(f):
    k,h=l.rstrip("\n").split("\t",1); r=norm(ref[k]); e+=ed(r,norm(h)); n+=len(r)
  print(f,"WER %.1f%%"%(100*e/n))
