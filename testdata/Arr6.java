import java.util.Arrays;

public class Arr6 {

    public static void main(String[] args) {
        int[] nums = new int[] { 5, 3, 9, 1 };
        System.out.println(nums.length);
        System.out.println(nums[0]);
        System.out.println(nums[3]);
        Arrays.sort(nums);
        System.out.println(nums[0]);
        System.out.println(nums[1]);
        System.out.println(nums[2]);
        System.out.println(nums[3]);

        int[] zeros = new int[3];
        System.out.println(zeros[0]);
        zeros[2] = 7;
        System.out.println(zeros[2]);
    }
}