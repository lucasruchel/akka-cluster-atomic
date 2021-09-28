package topologia;

import java.util.ArrayList;
import java.util.List;

public class VCubeTopology extends AbstractTopology {

    private int dim;

    public VCubeTopology(int n){
        this.dim = (int) Math.ceil(Math.log10(n)/Math.log10(2));
    }

    /***
     *
     * @param src origem da mensagem
     * @param p processo atual
     * @return vizinhos de p que estão na subarvore de src
     */
    public List<Integer> subtree(int src, int p){
        // processo de origem está mandando mensagem
        if (src == p){
            return neighborhood(src,dim);
        }else {
            return neighborhood(src,(cluster(src,p) - 1));
        }
    }



    /***
     *
     * @param p nó do hipercube em que será obtida a vizinhança
     * @param h quantidade de clusters do hipercubo que será obtido os processos diretamente conectados
     * @return Lista de inteiros com o vizinhos
     */
    public List<Integer> neighborhood(int p, int h){
        List<Integer> elements = new ArrayList<Integer>();

        // Verifica todos os cluster de 1 até h
        for (int i = 1; i <= h; i++) {
            int e = ff_neighboor(p,i);
            if (e != -1)
                elements.add(e);
        }

        return elements;
    }

    /***
     *
     * @param i nó a ser verificado
     * @param s cluster que será utilizado
     * @return -1 se não encontrar nenhum nó livre de falhas; o número do processo vizinho do nó i
     */

    public int ff_neighboor(int i, int s){
        List<Integer> elements = new ArrayList<Integer>();

        cis(elements, i, s);

        // procura primeiro elemento no cluster J que seja livre de falhas
        int n = 0;
        do {
            int e = elements.get(n);
            if (corrects.containsKey(e))
                return e;
            n++;
        } while (n < elements.size());

        // Nenhum vizinho sem falha encontrado
        return -1;
    }

    public void cis(List<Integer> elements, int id, int cluster){
        // Primeiro elemento do cluster
        int xor = id ^ (int)(Math.pow(2, cluster - 1));

        // Adiciona elemento ao cluster
        elements.add(xor);

        for (int i = 1; i <= cluster - 1; i++) {
            cis(elements,xor,i);
        }

    }

    public int cluster(int i, int j){
        return (MSB(i,j) + 1);
    }



    public int MSB(int i, int j) {
        int s = 0;
        for (int k = i ^ j; k > 0; k = k >> 1) {
            s++;
        }
        return --s;
    }


    // Retorna todos os vizinhos
    @Override
    public List<Integer> destinations(int me) {
        return this.subtree(me,me);
    }

    @Override
    public List<Integer> destinations(int me, int source) {
        return this.subtree(me,source);
    }

    @Override
    public int nextNeighboor(int me, int old) {
        int cluster = this.cluster(me, old);


        return this.ff_neighboor(me, cluster);
    }

    @Override
    public List<Integer> fathers(int p, int root) {
        List<Integer> superTree = new ArrayList<>();

        getFathers(superTree,root,p);

        return superTree;
    }


    private void getFathers(List<Integer> elem, int root,int p){

       elem.add(root);

       if (root == p)
           return;

       int n = ff_neighboor(root, cluster(root,p));

       getFathers(elem,n,p);
    }

//    Arredonda para cima 3.13, por exemplo, indicará que terá 4 dimensões
    public int log2(int v){
        return  (int) Math.ceil(Math.log(v)/Math.log(2));
    }
}
