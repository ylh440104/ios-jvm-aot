public class ArraysTest {

    public static void main(String[] args) {
        int[] arr = new int[5];
        int i = 0;
        while (i < 5) {
            arr[i] = i * i;
            i = i + 1;
        }
        int sum = 0;
        i = 0;
        while (i < 5) {
            sum = sum + arr[i];
            i = i + 1;
        }
        System.out.println(sum);
        System.out.println(arr.length);

        int[] b = new int[3];
        System.out.println(b[0]);

        Box[] boxes = new Box[2];
        boxes[0] = new Box(11);
        boxes[1] = new Box(31);
        System.out.println(boxes[0].v + boxes[1].v);
        System.out.println(boxes.length);
    }

    static class Box {
        int v;

        Box(int x) {
            this.v = x;
        }
    }
}